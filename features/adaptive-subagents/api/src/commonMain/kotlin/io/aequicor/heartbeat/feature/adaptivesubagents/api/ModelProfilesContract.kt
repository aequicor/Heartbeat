package io.aequicor.heartbeat.feature.adaptivesubagents.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.coroutines.flow.StateFlow

/** The profile's library of model descriptions and adaptive subagent preferences. */
public sealed interface ModelProfilesState : MachineState {
    /** Not started yet. */
    public data object Idle : ModelProfilesState

    /** Reading the stored library. */
    public data object Loading : ModelProfilesState

    /**
     * The library: at most one description per [ModelKey], and running [research]. [revision] grows with every
     * change so the effect handler can drop a save that arrives after a newer one.
     */
    public data class Ready(
        val profiles: List<ModelProfile> = emptyList(),
        val preferences: SubagentPreferences = SubagentPreferences(),
        val revision: Long = 0,
        val research: List<ResearchJob> = emptyList(),
    ) : ModelProfilesState {
        /** The description of [key], or null. */
        public fun profileOf(key: ModelKey): ModelProfile? = profiles.firstOrNull { it.key == key }

        /** The running research of [key], or null. */
        public fun researchOf(key: ModelKey): ResearchJob? = research.firstOrNull { it.subject.key == key }

        override fun toString(): String =
            "Ready(profiles=${profiles.size}, research=${research.size}, revision=$revision)"
    }

    /** The stored library could not be read; nothing is overwritten until a successful reload. */
    public data object Failed : ModelProfilesState
}

/** Commands from other features and screens, and results of effects. */
public sealed interface ModelProfilesIntent : MachineIntent {
    /** Commands sent through [ModelProfilesMachineKey]. */
    public sealed interface Public : ModelProfilesIntent {
        /** Loads the library; sent once by its owner when the machine is created. */
        public data object Start : Public

        /** Reads the library again after a failure. */
        public data object Reload : Public

        /**
         * Creates or replaces the description of [profile]'s key. [expectedRevision] is the stored revision the
         * edit started from, null for a new description; a different stored one rejects the save as a conflict.
         */
        public data class Save(val profile: ModelProfile, val expectedRevision: Long?) : Public

        /** Deletes the description of [key]; selection falls back to heuristics. */
        public data class Delete(val key: ModelKey) : Public

        /** Replaces the preferences. */
        public data class SetPreferences(val preferences: SubagentPreferences) : Public

        /**
         * Researches a description of [subject] on the [researcher] model; the caller supplies a fresh [jobId]. The
         * result replaces the stored description, so screens confirm before researching an edited one.
         */
        public data class Research(val jobId: String, val subject: ResearchSubject, val researcher: EngineTarget) :
            Public

        /** Stops the research of [key]. */
        public data class CancelResearch(val key: ModelKey) : Public
    }

    /** Results of effects. */
    public sealed interface Internal : ModelProfilesIntent {
        /** The stored library was read. */
        public data class Loaded(val profiles: List<ModelProfile>, val preferences: SubagentPreferences) : Internal {
            override fun toString(): String = "Loaded(profiles=${profiles.size})"
        }

        /** The stored library could not be read. */
        public data object LoadFailed : Internal

        /** The library could not be written; the in-memory library is kept for this run. */
        public data object SaveFailed : Internal

        /** The research [jobId] reached [stage] having read [sources] pages. */
        public data class ResearchProgress(val jobId: String, val stage: ResearchStage, val sources: Int) : Internal

        /** The research [jobId] wrote [profile]. */
        public data class ResearchCompleted(val jobId: String, val profile: ModelProfile) : Internal

        /** The research [jobId] failed. */
        public data class ResearchFailed(val jobId: String, val failure: ResearchFailure) : Internal
    }
}

/** IO commands executed in the feature impl. */
public sealed interface ModelProfilesEffect : MachineEffect {
    /** Reads the stored library. */
    public data object Load : ModelProfilesEffect

    /** Replaces the stored library; ignored when a save of a newer [revision] already ran. */
    public data class Persist(
        val profiles: List<ModelProfile>,
        val preferences: SubagentPreferences,
        val revision: Long,
    ) : ModelProfilesEffect {
        override fun toString(): String = "Persist(profiles=${profiles.size}, revision=$revision)"
    }

    /** Runs [job]: facts, search, reading and synthesis; reports progress and the result as internal intents. */
    public data class Research(val job: ResearchJob) : ModelProfilesEffect

    /** Stops the research [jobId]; its late results are ignored. */
    public data class StopResearch(val jobId: String) : ModelProfilesEffect
}

/** Why a save was rejected. */
public enum class SaveRejection {
    /** The description is invalid (see [ProfileProblem]). */
    Invalid,

    /** It was changed since the edit started. */
    Conflict,

    /** The library holds [ProfileLimits.MAX_PROFILES] descriptions already. */
    Limit,
}

/** One-shot notifications. */
public sealed interface ModelProfilesOutput : MachineOutput {
    /** The save of [key] was rejected. */
    public data class Rejected(val key: ModelKey, val reason: SaveRejection) : ModelProfilesOutput

    /** The library could not be read or written. */
    public data object StorageFailed : ModelProfilesOutput

    /** The research of [key] ended: with a saved description when [failure] is null. */
    public data class ResearchFinished(val key: ModelKey, val failure: ResearchFailure?) : ModelProfilesOutput
}

/**
 * Profile-scoped library of model descriptions. Created by the first injection of [ModelProfilesView] while
 * adaptive subagents are on; until then `MachineRegistry.send` reports `NotRunning`.
 */
public object ModelProfilesMachineKey :
    MachineKey<
        ModelProfilesState,
        ModelProfilesIntent,
        ModelProfilesIntent.Public,
        ModelProfilesEffect,
        ModelProfilesOutput,
    > {
    override val name: String = "model-profiles"
}

/** Read-only view of the library for other features; changes go through [ModelProfilesMachineKey]. */
public interface ModelProfilesView {
    /** Current library state. */
    public val state: StateFlow<ModelProfilesState>
}
