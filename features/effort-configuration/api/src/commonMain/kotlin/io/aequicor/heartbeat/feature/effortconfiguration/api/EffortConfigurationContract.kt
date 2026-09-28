package io.aequicor.heartbeat.feature.effortconfiguration.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** A user-selected native effort identifier for one model route. */
@Serializable
public data class EffortChoice(val target: EngineTarget, val effort: String) {
    init {
        require(effort.isNotBlank()) { "Empty effort" }
    }
}

/** Profile-owned effort preferences. Choices are stored per model route and survive restarts. */
public sealed interface EffortConfigurationState : MachineState {
    /** Not started yet. */
    public data object Idle : EffortConfigurationState

    /** Reading stored choices. */
    public data object Loading : EffortConfigurationState

    /** Choices are known; at most one per target. */
    public data class Ready(val choices: List<EffortChoice> = emptyList()) : EffortConfigurationState {
        /** Stored effort for [target], or null for the engine's native default. */
        public fun effortFor(target: EngineTarget): String? = choices.firstOrNull { it.target == target }?.effort
    }
}

/** Commands from other features and results of effects. */
public sealed interface EffortConfigurationIntent : MachineIntent {
    /** Commands sent through [EffortConfigurationMachineKey]. */
    public sealed interface Public : EffortConfigurationIntent {
        /** Loads stored choices; sent once by the owner when the machine is created. */
        public data object Start : Public

        /** Selects [effort] for [target]; null restores the engine's native default. */
        public data class Select(val target: EngineTarget, val effort: String?) : Public {
            init {
                require(effort == null || effort.isNotBlank()) { "Empty effort" }
            }
        }
    }

    /** Results of effects. */
    public sealed interface Internal : EffortConfigurationIntent {
        /** Stored choices were read. */
        public data class Loaded(val choices: List<EffortChoice>) : Internal

        /** Stored choices could not be read; the machine continues with native defaults. */
        public data object LoadFailed : Internal

        /** Choices could not be written; the in-memory selection is kept for this run. */
        public data object SaveFailed : Internal
    }
}

/** IO commands executed in the feature impl. */
public sealed interface EffortConfigurationEffect : MachineEffect {
    /** Reads stored choices. */
    public data object Load : EffortConfigurationEffect

    /** Replaces stored choices with [choices]. */
    public data class Save(val choices: List<EffortChoice>) : EffortConfigurationEffect
}

/** One-shot notifications. */
public sealed interface EffortConfigurationOutput : MachineOutput {
    /** Choices were not persisted. */
    public data object SaveFailed : EffortConfigurationOutput
}

/**
 * Profile-scoped effort machine. It is created by the first injection of [EffortChoicesView] in the profile;
 * until then `MachineRegistry.send` reports `NotRunning`.
 */
public object EffortConfigurationMachineKey :
    MachineKey<
        EffortConfigurationState,
        EffortConfigurationIntent,
        EffortConfigurationIntent.Public,
        EffortConfigurationEffect,
        EffortConfigurationOutput,
    > {
    override val name: String = "effort-configuration"
}

/**
 * Read-only view of the profile's effort choices for other features. Injecting it starts the machine;
 * choices are changed only with `MachineRegistry.send(EffortConfigurationMachineKey, Public.Select(...))`.
 */
public interface EffortChoicesView {
    /** Current machine state; null effort (native default) until [EffortConfigurationState.Ready]. */
    public val state: StateFlow<EffortConfigurationState>
}

/**
 * Persists effort choices in the profile; while off, choices live only until the profile closes. Read when the
 * profile loads and on every save: turning it on in a running profile saves current choices on the next change,
 * previously stored ones are read after the profile restarts.
 */
public val EffortConfiguration: FeatureToggle.Flag = FeatureToggle.Flag(
    "ai.effort_configuration",
    "Настройка уровня effort моделей",
    default = false,
)

/**
 * Effort to send for [target]: the stored choice only while the model still advertises it in [supported]
 * (see `ModelInfo.reasoningEfforts`), otherwise null so the engine applies its native default.
 */
public fun EffortConfigurationState.effectiveEffort(target: EngineTarget, supported: List<String>): String? =
    (this as? EffortConfigurationState.Ready)?.effortFor(target)?.takeIf { it in supported }
