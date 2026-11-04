package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId

/**
 * Everything the settings space shows: engines with their bindings, the sources those bindings use,
 * cached models of every binding and the user's model choice. Reading it never triggers discovery.
 * [management] lists every registered engine, switched-off ones too, which the engine control panel manages;
 * it is [EngineManagementState.Off] while engine management is off.
 */
public data class ConnectionsSnapshot(
    val engines: List<EngineInfo>,
    val sources: List<AuthSource>,
    val models: Map<EngineBindingId, ModelCatalogSnapshot>,
    val selection: ModelSelection,
    val management: EngineManagementState = EngineManagementState.Off,
    val checks: List<ModelCheckReceipt> = emptyList(),
)

/** A change requested in the settings space; one runs at a time. */
public sealed interface ConnectionOperation {
    /** A source edit in progress; credentials are carried only by the intent and effect, never by state. */
    public data class Edit(val binding: EngineBindingId) : ConnectionOperation

    /** Explicitly probes the engine installation. */
    public data class ProbeEngine(val engine: EngineId) : ConnectionOperation

    /** Enables or disables new executions through a binding; its models stay chosen. */
    public data class SetConnectionEnabled(val binding: EngineBindingId, val isEnabled: Boolean) : ConnectionOperation

    /**
     * Interrupts executions through the connection and removes its binding and model choice, keeping chat history.
     * The source is forgotten when no other binding uses it.
     */
    public data class Disconnect(val binding: EngineBindingId) : ConnectionOperation

    /** Explicit model discovery through a binding. */
    public data class RefreshModels(val engine: EngineId, val binding: EngineBindingId) : ConnectionOperation

    /** Offers or hides one model in model pickers. */
    public data class SetModelEnabled(val target: EngineTarget, val isEnabled: Boolean) : ConnectionOperation

    /**
     * Replaces all enabled models of a binding (select all / none) when [previous] is null. With a baseline, only
     * the user's delta from [previous] to [models] is applied atomically to the latest stored set, preserving
     * concurrent changes outside that delta. The default is cleared only when the resulting set disables it; no
     * replacement default is chosen implicitly.
     */
    public data class SetModelsEnabled(
        val binding: EngineBindingId,
        val models: Set<ModelId>,
        val previous: Set<ModelId>? = null,
    ) : ConnectionOperation

    /** Sets or clears the profile default model. */
    public data class SetDefaultModel(val target: EngineTarget?) : ConnectionOperation

    /** Repeats the two probes for this exact selected route without changing choices or the default. */
    public data class CheckModel(val target: EngineTarget) : ConnectionOperation
}

/** An operation that failed, kept until retried or dismissed. */
public data class FailedOperation(val operation: ConnectionOperation, val failure: EngineFailure)

/** Settings space state. */
public sealed interface EngineConnectionsState : MachineState {
    /** Not started. */
    public data object Idle : EngineConnectionsState

    /**
     * Observing; [snapshot] is null until the first one arrives and after the observation fails with [loadFailure].
     * A failed observation keeps the state, so a [pending] change still reports its outcome.
     */
    public data class Active(
        val snapshot: ConnectionsSnapshot? = null,
        val pending: ConnectionOperation? = null,
        val failed: FailedOperation? = null,
        val loadFailure: EngineFailure? = null,
        val operationSequence: Long = 0,
        val phase: ModelSavePhase? = null,
        /** Most recent bounded save ended before its write outcome was known; snapshots show actual choices. */
        val isSavePending: Boolean = false,
    ) : EngineConnectionsState
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

        /** The effect owns the key after acceptance; an ignored request leaves it with the sender. */
        public data class Edit(val edit: ConnectionEdit) : Public

        /** Repeats the failed change at user request only. */
        public data object RetryFailed : Public

        /** Dismisses the failure. */
        public data object DismissError : Public
    }

    /** Effect results. */
    public sealed interface Internal : EngineConnectionsIntent {
        /** A fresh snapshot. */
        public data class Snapshot(val snapshot: ConnectionsSnapshot) : Internal

        /** The choice was saved; checking remains part of the same effect and uses stay. */
        public data class Checking(val operation: ConnectionOperationId) : Internal

        /** The pending operation ended its UI wait; pending save is not a rollback or a success claim. */
        public data class Applied(val operation: ConnectionOperationId, val isSavePending: Boolean = false) : Internal

        /** The pending change failed. */
        public data class ApplyFailed(val failure: EngineFailure, val operation: ConnectionOperationId) : Internal

        /** Observation failed. */
        public data class ObserveFailed(val failure: EngineFailure) : Internal
    }
}

/** IO of the settings space. */
public sealed interface EngineConnectionsEffect : MachineEffect {
    /** Observes engines, sources, cached models and the model choice until the state is left. */
    public data object Observe : EngineConnectionsEffect

    /** Executes one change. */
    public data class Execute(
        val operation: ConnectionOperation,
        val id: ConnectionOperationId = ConnectionOperationId(1),
        val checkTargets: Set<EngineTarget> = emptySet(),
        val removedTargets: Set<EngineTarget> = emptySet(),
    ) : EngineConnectionsEffect

    /** Executes a conditional source edit and checks its enabled routes. */
    public data class Edit(
        val request: ConnectionEdit,
        val id: ConnectionOperationId,
        val checkTargets: Set<EngineTarget>,
        val relatedBindings: Set<EngineBindingId>,
    ) : EngineConnectionsEffect
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
 * | Active | Snapshot | | stay (load failure cleared) | |
 * | Active | Apply | observed, nothing pending | stay (pending) | Execute |
 * | Active | Edit | observed, nothing pending | stay (pending without credentials) | Edit |
 * | Active | Checking | same pending operation | stay (phase Checking) | |
 * | Active | Applied / ApplyFailed | same pending operation | stay | |
 * | Active | RetryFailed | observed, nothing pending, has failure | stay (pending) | Execute |
 * | Active | DismissError | | stay | |
 * | Active | ObserveFailed | | stay (snapshot dropped, load failure) | |
 * | Active | RetryLoad | has load failure | stay | Observe |
 *
 * Changes and observation failures use `stay`, so neither cancels the other while it runs.
 * Failed changes are never replayed automatically: a disconnect or a model refresh may have partially completed,
 * and a source edit requires the user to submit its key again instead of RetryFailed.
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
            on<EngineConnectionsIntent.Internal.Snapshot> {
                stay { state.copy(snapshot = intent.snapshot, loadFailure = null) }
            }
            on<EngineConnectionsIntent.Public.Apply>(
                guard = {
                    state.pending == null && state.snapshot != null && intent.operation !is ConnectionOperation.Edit &&
                        state.snapshot.acceptsCheck(intent.operation)
                },
            ) {
                stay {
                    state.copy(
                        pending = intent.operation,
                        failed = null,
                        operationSequence = state.operationSequence + 1,
                        phase = intent.operation.modelPhase(),
                        isSavePending = false,
                    )
                }
                effect { state.execute(intent.operation) }
            }
            on<EngineConnectionsIntent.Public.Edit>(guard = { state.pending == null && state.snapshot != null }) {
                stay {
                    state.copy(
                        pending = ConnectionOperation.Edit(intent.edit.binding),
                        failed = null,
                        operationSequence = state.operationSequence + 1,
                        phase = ModelSavePhase.Saving,
                        isSavePending = false,
                    )
                }
                effect { state.edit(intent.edit) }
            }
            on<EngineConnectionsIntent.Internal.Checking>(
                guard = { state.matches(intent.operation) && state.phase != null },
            ) {
                stay { state.copy(phase = ModelSavePhase.Checking) }
            }
            on<EngineConnectionsIntent.Internal.Applied>(guard = { state.matches(intent.operation) }) {
                stay { state.copy(pending = null, phase = null, isSavePending = intent.isSavePending) }
            }
            on<EngineConnectionsIntent.Internal.ApplyFailed>(guard = { state.matches(intent.operation) }) {
                stay {
                    state.copy(
                        pending = null,
                        failed = state.pending?.let { FailedOperation(it, intent.failure) },
                        phase = null,
                        isSavePending = false,
                    )
                }
            }
            on<EngineConnectionsIntent.Public.RetryFailed>(
                guard = {
                    state.pending == null && state.failed != null &&
                        state.failed?.operation !is ConnectionOperation.Edit && state.snapshot != null &&
                        state.failed?.operation?.let { state.snapshot.acceptsCheck(it) } == true
                },
            ) {
                stay {
                    state.copy(
                        pending = state.failed?.operation,
                        failed = null,
                        operationSequence = state.operationSequence + 1,
                        phase = state.failed?.operation?.modelPhase(),
                        isSavePending = false,
                    )
                }
                effect { state.failed?.operation?.let { state.execute(it) } }
            }
            on<EngineConnectionsIntent.Public.DismissError> { stay { state.copy(failed = null) } }
            on<EngineConnectionsIntent.Internal.ObserveFailed> {
                stay { state.copy(snapshot = null, loadFailure = intent.failure) }
            }
            on<EngineConnectionsIntent.Public.RetryLoad>(guard = { state.loadFailure != null }) {
                stay { state.copy(loadFailure = null) }
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
                    effect.id,
                )

                is EngineConnectionsEffect.Edit -> EngineConnectionsIntent.Internal.ApplyFailed(
                    error.toEngineFailure(),
                    effect.id,
                )
            }
        }
    }

private fun EngineConnectionsState.Active.matches(id: ConnectionOperationId): Boolean =
    pending != null && id.value == operationSequence

private fun ConnectionOperation.modelPhase(): ModelSavePhase? = when (this) {
    is ConnectionOperation.SetModelEnabled,
    is ConnectionOperation.SetModelsEnabled,
    is ConnectionOperation.SetDefaultModel,
    is ConnectionOperation.Edit,
    -> ModelSavePhase.Saving

    is ConnectionOperation.CheckModel -> ModelSavePhase.Checking

    is ConnectionOperation.ProbeEngine,
    is ConnectionOperation.SetConnectionEnabled,
    is ConnectionOperation.Disconnect,
    is ConnectionOperation.RefreshModels,
    -> null
}
