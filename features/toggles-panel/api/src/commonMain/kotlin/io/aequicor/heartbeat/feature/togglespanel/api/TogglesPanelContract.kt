package io.aequicor.heartbeat.feature.togglespanel.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.ToggleState
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.machineSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Device-local feature flag settings, available before sign-in. [isEmbedded] marks the route shown as a section
 * of the settings window: the panel then draws only its content. Opened elsewhere it redirects to that section
 * while unified settings are on, and otherwise shows its own header with "back".
 */
@Serializable
@SerialName("toggles_panel")
public data class TogglesPanelRoute(val isEmbedded: Boolean = false) : Route

/** Type-safe local override operations. */
public sealed interface ToggleOperation {
    /** Sets a boolean local override. */
    public data class SetFlag(val toggle: FeatureToggle.Flag, val isEnabled: Boolean) : ToggleOperation

    /** Sets one of the declared choice options. */
    public data class SetChoice(val toggle: FeatureToggle.Choice, val value: String) : ToggleOperation

    /** Removes one local override. */
    public data class Reset(val toggle: FeatureToggle<*>) : ToggleOperation

    /** Removes all local overrides, including stale keys. */
    public data object ResetAll : ToggleOperation
}

/** Transient panel state; persisted values are owned by FeatureToggleControl. */
public sealed interface TogglesPanelState : MachineState {
    /** The component has not started its workflow yet. */
    public data object Idle : TogglesPanelState

    /** Observed values with at most one pending or failed mutation. */
    public data class Active(
        val rows: List<ToggleState<*>>? = null,
        val pending: ToggleOperation? = null,
        val failed: ToggleOperation? = null,
    ) : TogglesPanelState

    /** Observation failed and can be restarted explicitly. */
    public data object LoadError : TogglesPanelState
}

/** Panel requests and storage observations. */
public sealed interface TogglesPanelIntent : MachineIntent {
    /** Intents accepted from screens and other features. */
    public sealed interface Public : TogglesPanelIntent {
        /** Starts the feature workflow once. */
        public data object Start : Public

        /** Restarts failed observation. */
        public data object RetryLoad : Public

        /** Requests a serialized local mutation. */
        public data class Apply(val operation: ToggleOperation) : Public

        /** Retries the last failed mutation only at user request. */
        public data object RetryWrite : Public

        /** Dismisses the previous write failure. */
        public data object DismissError : Public
    }

    /** Results and lifecycle acknowledgements produced inside this feature. */
    public sealed interface Internal : TogglesPanelIntent {
        /** A fresh snapshot from the toggle controller. */
        public data class Snapshot(val rows: List<ToggleState<*>>) : Internal

        /** The pending mutation completed successfully. */
        public data object Written : Internal

        /** The pending mutation failed; retain the last observed values. */
        public data object WriteFailed : Internal

        /** The observation stream failed. */
        public data object ObserveFailed : Internal
    }
}

/** The observation remains alive during writes because Active uses stay transitions. */
public sealed interface TogglesPanelEffect : MachineEffect {
    /** Observes toggle values until the feature scope closes. */
    public data object Observe : TogglesPanelEffect

    /** Executes a single local mutation. */
    public data class Write(val operation: ToggleOperation) : TogglesPanelEffect
}

/** No ephemeral output is needed for local settings. */
public sealed interface TogglesPanelOutput : MachineOutput

/** Address of the flag panel machine. */
public object TogglesPanelMachineKey :
    MachineKey<
        TogglesPanelState,
        TogglesPanelIntent,
        TogglesPanelIntent.Public,
        TogglesPanelEffect,
        TogglesPanelOutput,
    > {
    override val name: String = "toggles_panel"
}

/**
 * Idle --Start--> Active + Observe; Active --Snapshot/Apply/Written/WriteFailed--> Active (stay).
 * Only one write is accepted at a time. ObserveFailed --> LoadError --RetryLoad--> Active + Observe.
 * Failed writes retain the last rows and require explicit retry; transient writes are never persisted/replayed.
 */
public val TogglesPanelMachineSpec:
    MachineSpec<TogglesPanelState, TogglesPanelIntent, TogglesPanelEffect, TogglesPanelOutput> =
    machineSpec(TogglesPanelMachineKey, TogglesPanelState.Idle) {
        state<TogglesPanelState.Idle> {
            on<TogglesPanelIntent.Public.Start> {
                goto<TogglesPanelState.Active> { TogglesPanelState.Active() }
                effect { TogglesPanelEffect.Observe }
            }
        }
        state<TogglesPanelState.Active> {
            on<TogglesPanelIntent.Internal.Snapshot> { stay { state.copy(rows = intent.rows) } }
            on<TogglesPanelIntent.Public.Apply>(guard = { state.pending == null && state.rows != null }) {
                stay { state.copy(pending = intent.operation, failed = null) }
                effect { TogglesPanelEffect.Write(intent.operation) }
            }
            on<TogglesPanelIntent.Internal.Written> { stay { state.copy(pending = null) } }
            on<TogglesPanelIntent.Internal.WriteFailed> { stay { state.copy(pending = null, failed = state.pending) } }
            on<TogglesPanelIntent.Public.RetryWrite>(guard = { state.pending == null && state.failed != null }) {
                stay { state.copy(pending = state.failed, failed = null) }
                effect { state.failed?.let(TogglesPanelEffect::Write) }
            }
            on<TogglesPanelIntent.Public.DismissError> { stay { state.copy(failed = null) } }
            on<TogglesPanelIntent.Internal.ObserveFailed> {
                goto<TogglesPanelState.LoadError> { TogglesPanelState.LoadError }
            }
        }
        state<TogglesPanelState.LoadError> {
            on<TogglesPanelIntent.Public.RetryLoad> {
                goto<TogglesPanelState.Active> { TogglesPanelState.Active() }
                effect { TogglesPanelEffect.Observe }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                TogglesPanelEffect.Observe -> TogglesPanelIntent.Internal.ObserveFailed
                is TogglesPanelEffect.Write -> TogglesPanelIntent.Internal.WriteFailed
            }
        }
    }
