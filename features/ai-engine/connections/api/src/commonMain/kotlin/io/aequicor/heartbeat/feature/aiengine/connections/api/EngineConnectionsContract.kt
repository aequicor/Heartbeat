package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId

/**
 * Everything the settings space shows: engines with their bindings, the sources those bindings use,
 * cached models of every binding and the user's model choice. Reading it never triggers discovery.
 */
public data class ConnectionsSnapshot(
    val engines: List<EngineInfo>,
    val sources: List<AuthSource>,
    val models: Map<EngineBindingId, ModelCatalogSnapshot>,
    val selection: ModelSelection,
)

/** A change requested in the settings space; one runs at a time. */
public sealed interface ConnectionOperation {
    /** Explicitly probes the engine installation. */
    public data class ProbeEngine(val engine: EngineId) : ConnectionOperation

    /** Enables or disables new executions through a binding; its models stay chosen. */
    public data class SetConnectionEnabled(val binding: EngineBindingId, val isEnabled: Boolean) : ConnectionOperation

    /** Removes the binding and its model choice; the source is forgotten when no other binding uses it. */
    public data class Disconnect(val binding: EngineBindingId) : ConnectionOperation

    /** Explicit model discovery through a binding. */
    public data class RefreshModels(val engine: EngineId, val binding: EngineBindingId) : ConnectionOperation

    /** Offers or hides one model in model pickers. */
    public data class SetModelEnabled(val target: EngineTarget, val isEnabled: Boolean) : ConnectionOperation

    /** Replaces all enabled models of a binding (select all / none). */
    public data class SetModelsEnabled(val binding: EngineBindingId, val models: Set<ModelId>) : ConnectionOperation

    /** Sets or clears the profile default model. */
    public data class SetDefaultModel(val target: EngineTarget?) : ConnectionOperation
}

/** An operation that failed, kept until retried or dismissed. */
public data class FailedOperation(val operation: ConnectionOperation, val failure: EngineFailure)

/** Settings space state. */
public sealed interface EngineConnectionsState : MachineState {
    /** Not started. */
    public data object Idle : EngineConnectionsState

    /** Observing; [snapshot] is null until the first one arrives. */
    public data class Active(
        val snapshot: ConnectionsSnapshot? = null,
        val pending: ConnectionOperation? = null,
        val failed: FailedOperation? = null,
    ) : EngineConnectionsState

    /** Observation failed and can be restarted explicitly. */
    public data class LoadError(val failure: EngineFailure) : EngineConnectionsState
}

/** Settings requests and effect results. */
public sealed interface EngineConnectionsIntent : MachineIntent {
    /** Accepted from the settings screen and other features. */
    public sealed interface Public : EngineConnectionsIntent {
        /** Starts observing. */
        public data object Start : Public

        /** Restarts a failed observation. */
        public data object RetryLoad : Public

        /** Requests a serialized change. */
        public data class Apply(val operation: ConnectionOperation) : Public

        /** Repeats the failed change at user request only. */
        public data object RetryFailed : Public

        /** Dismisses the failure. */
        public data object DismissError : Public
    }

    /** Effect results. */
    public sealed interface Internal : EngineConnectionsIntent {
        /** A fresh snapshot. */
        public data class Snapshot(val snapshot: ConnectionsSnapshot) : Internal

        /** The pending change completed. */
        public data object Applied : Internal

        /** The pending change failed. */
        public data class ApplyFailed(val failure: EngineFailure) : Internal

        /** Observation failed. */
        public data class ObserveFailed(val failure: EngineFailure) : Internal
    }
}

/** IO of the settings space. */
public sealed interface EngineConnectionsEffect : MachineEffect {
    /** Observes engines, sources, cached models and the model choice until the state is left. */
    public data object Observe : EngineConnectionsEffect

    /** Executes one change. */
    public data class Execute(val operation: ConnectionOperation) : EngineConnectionsEffect
}

/** The settings space emits no one-off results. */
public sealed interface EngineConnectionsOutput : MachineOutput

/** Address of the settings space machine. */
public object EngineConnectionsMachineKey :
    MachineKey<
        EngineConnectionsState,
        EngineConnectionsIntent,
        EngineConnectionsIntent.Public,
        EngineConnectionsEffect,
        EngineConnectionsOutput,
    > {
    override val name: String = "engine_connections"
}

/**
 * | From | Intent | Guard | To | Effect |
 * |---|---|---|---|---|
 * | Idle | Start | | Active | Observe |
 * | Active | Snapshot | | stay | |
 * | Active | Apply | observed, nothing pending | stay (pending) | Execute |
 * | Active | Applied / ApplyFailed | | stay | |
 * | Active | RetryFailed | nothing pending, has failure | stay (pending) | Execute |
 * | Active | DismissError | | stay | |
 * | Active | ObserveFailed | | LoadError | |
 * | LoadError | RetryLoad | | Active | Observe |
 *
 * Changes use `stay`, so the observation keeps running while they execute. Failed changes are never replayed
 * automatically: a disconnect or a model refresh may have partially completed.
 */
public val EngineConnectionsMachineSpec:
    MachineSpec<EngineConnectionsState, EngineConnectionsIntent, EngineConnectionsEffect, EngineConnectionsOutput> =
    machineSpec(EngineConnectionsMachineKey, EngineConnectionsState.Idle) {
        state<EngineConnectionsState.Idle> {
            on<EngineConnectionsIntent.Public.Start> {
                goto<EngineConnectionsState.Active> { EngineConnectionsState.Active() }
                effect { EngineConnectionsEffect.Observe }
            }
        }
        state<EngineConnectionsState.Active> {
            on<EngineConnectionsIntent.Internal.Snapshot> { stay { state.copy(snapshot = intent.snapshot) } }
            on<EngineConnectionsIntent.Public.Apply>(guard = { state.pending == null && state.snapshot != null }) {
                stay { state.copy(pending = intent.operation, failed = null) }
                effect { EngineConnectionsEffect.Execute(intent.operation) }
            }
            on<EngineConnectionsIntent.Internal.Applied> { stay { state.copy(pending = null) } }
            on<EngineConnectionsIntent.Internal.ApplyFailed> {
                stay { state.copy(pending = null, failed = state.pending?.let { FailedOperation(it, intent.failure) }) }
            }
            on<EngineConnectionsIntent.Public.RetryFailed>(guard = { state.pending == null && state.failed != null }) {
                stay { state.copy(pending = state.failed?.operation, failed = null) }
                effect { state.failed?.operation?.let(EngineConnectionsEffect::Execute) }
            }
            on<EngineConnectionsIntent.Public.DismissError> { stay { state.copy(failed = null) } }
            on<EngineConnectionsIntent.Internal.ObserveFailed> {
                goto<EngineConnectionsState.LoadError> { EngineConnectionsState.LoadError(intent.failure) }
            }
        }
        state<EngineConnectionsState.LoadError> {
            on<EngineConnectionsIntent.Public.RetryLoad> {
                goto<EngineConnectionsState.Active> { EngineConnectionsState.Active() }
                effect { EngineConnectionsEffect.Observe }
            }
        }
        onEffectFailure { effect, error ->
            when (effect) {
                EngineConnectionsEffect.Observe -> EngineConnectionsIntent.Internal.ObserveFailed(
                    error.toEngineFailure(),
                )

                is EngineConnectionsEffect.Execute -> EngineConnectionsIntent.Internal.ApplyFailed(
                    error.toEngineFailure(),
                )
            }
        }
    }
