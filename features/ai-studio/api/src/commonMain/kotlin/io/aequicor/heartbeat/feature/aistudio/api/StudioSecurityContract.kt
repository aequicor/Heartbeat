package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Profile choice for ordinary studio turns; helpers and other hosts retain their own trust restrictions. */
@Serializable
public enum class StudioSecurityLevel {
    /** Ask the user before actions with side effects. */
    Ask,

    /** Allow only actions accepted by independent automatic safety review. The initial profile choice. */
    Automatic,

    /** Trust actions within the engine's approval policy; independent host restrictions still apply. */
    Trust,
}

/** Profile security settings, also embedded in the unified settings window. */
@Serializable
@SerialName("studio_security")
public data class StudioSecurityRoute(val isEmbedded: Boolean = false) : Route

/** Persisted security policy is changed only after its write succeeds. Screen closure never owns that write. */
public sealed interface StudioSecurityState : MachineState {
    /** Storage has not been requested yet. */
    public data object Idle : StudioSecurityState

    /** Resolving the profile's saved choice before submitting a turn or enabling editing. */
    public data object Loading : StudioSecurityState

    /** Storage was unreadable. Turns use Ask until Retry loads the actual choice. */
    public data object LoadError : StudioSecurityState

    /** The accepted choice; a failed write retains this level and exposes the failure to the screen. */
    public data class Ready(
        val level: StudioSecurityLevel = StudioSecurityLevel.Automatic,
        val isSaveFailed: Boolean = false,
    ) : StudioSecurityState

    /** The previous level remains effective until [requested] is durably saved. */
    public data class Saving(val level: StudioSecurityLevel, val requested: StudioSecurityLevel) : StudioSecurityState
}

/** Host settings requests and results of profile storage. */
public sealed interface StudioSecurityIntent : MachineIntent {
    /** Requests from the settings screen and profile host. */
    public sealed interface Public : StudioSecurityIntent {
        /** Starts loading once. */
        public data object Start : Public

        /** Retries an unreadable profile setting. */
        public data object Retry : Public

        /** Selects the policy for subsequent ordinary studio turns. */
        public data class SetLevel(val level: StudioSecurityLevel) : Public
    }

    /** Feedback from storage; callers outside this feature cannot send it through the registry. */
    public sealed interface Internal : StudioSecurityIntent {
        /** A missing value means the initial Automatic policy. */
        public data class Loaded(val level: StudioSecurityLevel?) : Internal

        /** The saved value could not be resolved and must not be overwritten. */
        public data object LoadFailed : Internal

        /** The selected level was written successfully. */
        public data object Saved : Internal

        /** The selected level was not written; keep the preceding effective policy. */
        public data object SaveFailed : Internal
    }
}

/** Profile storage operations, executed by the implementation. */
public sealed interface StudioSecurityEffect : MachineEffect {
    /** Reads the profile's saved choice without creating a default entry. */
    public data object Load : StudioSecurityEffect

    /** Replaces the saved level; only one save is active at a time. */
    public data class Save(val level: StudioSecurityLevel) : StudioSecurityEffect
}

/** Reserved one-shot outputs; errors are persistent machine state so reopening the screen still shows them. */
public sealed interface StudioSecurityOutput : MachineOutput

/** Address of the single security settings machine owned by the active profile. */
public object StudioSecurityMachineKey : MachineKey<
    StudioSecurityState,
    StudioSecurityIntent,
    StudioSecurityIntent.Public,
    StudioSecurityEffect,
    StudioSecurityOutput,
> {
    override val name: String = "studio-security"
}
