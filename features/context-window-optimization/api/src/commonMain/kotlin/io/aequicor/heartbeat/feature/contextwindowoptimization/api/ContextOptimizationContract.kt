package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.flow.StateFlow

/** Profile-owned context optimization: preferences and the compactions of sessions. */
public sealed interface ContextOptimizationState : MachineState {
    /** Not started yet. */
    public data object Idle : ContextOptimizationState

    /** Reading stored settings. */
    public data object Loading : ContextOptimizationState

    /**
     * Settings are known. [pending] compactions wait for their session to become idle; a host claims one before
     * running it ([running]). [marks] count automatic attempts per session. [revision] grows with every settings
     * change so a stale save is dropped.
     */
    public data class Ready(
        val settings: ContextOptimizationSettings = ContextOptimizationSettings(),
        val pending: Map<SessionRef, PendingCompaction> = emptyMap(),
        val running: Map<SessionRef, PendingCompaction> = emptyMap(),
        val marks: Map<SessionRef, CompactionMark> = emptyMap(),
        val revision: Long = 0,
    ) : ContextOptimizationState
}

/** Commands from other features and results of effects. */
public sealed interface ContextOptimizationIntent : MachineIntent {
    /** Commands sent through [ContextOptimizationMachineKey]. */
    public sealed interface Public : ContextOptimizationIntent {
        /** Loads stored settings; sent once by the owner when the machine is created. */
        public data object Start : Public

        /** Replaces the settings and persists them. */
        public data class UpdateSettings(val settings: ContextOptimizationSettings) : Public

        /**
         * Asks to compact [session] when it is idle; replaces an earlier pending request of the session. Null
         * [method] follows the strategy of the settings. Ignored while a compaction of the session runs.
         */
        public data class Compact(
            val session: SessionRef,
            val origin: CompactionOrigin,
            val instructions: String? = null,
            val method: CompactionMethod? = null,
        ) : Public {
            init {
                require(instructions == null || instructions.isNotBlank())
            }

            override fun toString(): String =
                "Compact(origin=$origin, method=$method, instructions=${instructions?.length ?: 0})"
        }

        /**
         * A turn of [session] settled with the native occupancy [usage]; null means unknown. The host sends it
         * only while automatic compaction is enabled. It may make an automatic compaction pending.
         */
        public data class TurnSettled(
            val session: SessionRef,
            val usage: ContextUsage?,
            val support: CompactionSupport,
        ) : Public

        /** A host starts the pending compaction of [session]; another claim of the same session is ignored. */
        public data class Claim(val session: SessionRef) : Public

        /** The claimed compaction of [session] ended with [result]. */
        public data class Finished(val session: SessionRef, val result: CompactionResult) : Public

        /** The conversation of [old] continues in [new]: its pending request and marks follow it. */
        public data class RolledOver(val old: SessionRef, val new: SessionRef) : Public

        /** [session] is gone; its requests and marks are dropped. */
        public data class SessionClosed(val session: SessionRef) : Public
    }

    /** Results of effects. */
    public sealed interface Internal : ContextOptimizationIntent {
        /** Stored settings were read; null when nothing was saved. */
        public data class Loaded(val settings: ContextOptimizationSettings?) : Internal

        /** Settings could not be read; defaults apply. */
        public data object LoadFailed : Internal

        /** Settings could not be written; the in-memory settings are kept for this run. */
        public data object SaveFailed : Internal
    }
}

/** IO commands executed in the feature impl. */
public sealed interface ContextOptimizationEffect : MachineEffect {
    /** Reads stored settings. */
    public data object Load : ContextOptimizationEffect

    /** Writes [settings]; a save older than the last written [revision] is dropped. */
    public data class Save(val settings: ContextOptimizationSettings, val revision: Long) : ContextOptimizationEffect
}

/** One-shot notifications. */
public sealed interface ContextOptimizationOutput : MachineOutput {
    /** Settings were not persisted. */
    public data object SaveFailed : ContextOptimizationOutput
}

/**
 * Profile-scoped context optimization machine. It is created by the first injection of [ContextOptimizationView]
 * in the profile; until then `MachineRegistry.send` reports `NotRunning`.
 */
public object ContextOptimizationMachineKey :
    MachineKey<
        ContextOptimizationState,
        ContextOptimizationIntent,
        ContextOptimizationIntent.Public,
        ContextOptimizationEffect,
        ContextOptimizationOutput,
    > {
    override val name: String = "context-optimization"
}

/**
 * Read-only state for hosts that run compactions and for settings. Injecting it starts the machine; changes go only
 * through `MachineRegistry.send(ContextOptimizationMachineKey, Public…)`.
 */
public interface ContextOptimizationView {
    /** Current machine state. */
    public val state: StateFlow<ContextOptimizationState>
}
