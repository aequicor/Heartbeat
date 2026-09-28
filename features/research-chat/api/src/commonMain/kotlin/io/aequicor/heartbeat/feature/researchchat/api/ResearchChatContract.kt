package io.aequicor.heartbeat.feature.researchchat.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Experimental research conversations, available only for projectless Koog routes. */
public val ResearchChatEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "research_chat.enabled",
    "Чат-исследование: вопросы и общие источники вне проектов в Koog",
    default = false,
)

/** Opens the profile's research workspace with the selected Koog route for new research sessions. */
@Serializable
@SerialName("research_chat")
public data class ResearchChatRoute(val target: EngineTarget) : Route

/** Research workflow; accepted native turns belong to the profile and outlive this screen. */
public sealed interface ResearchChatState : MachineState {
    /** Not opened yet. */
    public data object Idle : ResearchChatState

    /** Reading persisted sessions and checking the feature switch. */
    public data class Loading(val target: EngineTarget) : ResearchChatState

    /** Initial loading failed and may be retried. */
    public data class Failed(val target: EngineTarget) : ResearchChatState

    /** Feature switch or route disallows research. */
    public data object Disabled : ResearchChatState

    /** Selected session/question plus observed profile data. Mutations never own generation lifetime. */
    public data class Ready(
        val target: EngineTarget,
        val workspace: ResearchWorkspace,
        val sessionId: String?,
        val questionId: String?,
        val isMutating: Boolean = false,
        val submitting: Set<String> = emptySet(),
        val hasError: Boolean = false,
        val isEnabled: Boolean = true,
    ) : ResearchChatState {
        /** Currently selected session, if it still exists. */
        public val session: ResearchSession? get() = workspace.sessions.firstOrNull { it.id == sessionId }

        /** Currently selected question, if it still exists. */
        public val question: ResearchQuestion? get() = session?.questions?.firstOrNull { it.id == questionId }

        /** Pending submission and active native execution both disable a second submit. */
        public val isRunning: Boolean get() = questionId in workspace.running || questionId in submitting
    }
}

/** User commands and repository observations. No source content is logged by intent formatting. */
public sealed interface ResearchChatIntent : MachineIntent {
    /** Commands accepted from the screen or other feature machines. */
    public sealed interface Public : ResearchChatIntent {
        /** Opens the workspace once. */
        public data class Start(val target: EngineTarget) : Public

        /** Retries initial loading. */
        public data object Retry : Public

        /** Creates a session with the route supplied when this workspace was opened. */
        public data object NewSession : Public

        /** Selects an existing session. */
        public data class SelectSession(val id: String) : Public

        /** Creates an independent question in the selected session. */
        public data object NewQuestion : Public

        /** Selects a question in the current session. */
        public data class SelectQuestion(val id: String) : Public

        /** Sends a follow-up in the selected question. */
        public data class Submit(val prompt: String) : Public {
            override fun toString(): String = "Submit(length=${prompt.length})"
        }

        /** Explicitly stops the currently selected native turn. */
        public data object Stop : Public

        /** Imports a source; every source added to the first question becomes shared. */
        public data class AddResource(
            val kind: ResearchResourceKind,
            val title: String,
            val value: String,
            val scope: ResearchResourceScope,
            val mediaType: String? = null,
        ) : Public {
            override fun toString(): String = "AddResource(kind=$kind, scope=$scope)"
        }

        /** Selects or deselects a source for the current question only. */
        public data class SetResourceSelected(val resourceId: String, val isSelected: Boolean) : Public

        /** Makes a question source available to every question. */
        public data class ShareResource(val resourceId: String) : Public

        /** Removes the source from the whole session and all question selections. */
        public data class RemoveResource(val resourceId: String) : Public

        /** Dismisses the generic, non-sensitive error indicator. */
        public data object DismissError : Public
    }

    /** Results from effects; not part of the cross-feature command surface. */
    public sealed interface Internal : ResearchChatIntent {
        /** Initial profile data and eligibility are ready. */
        public data class Loaded(val workspace: ResearchWorkspace, val isEnabled: Boolean) : Internal

        /** A profile snapshot changed. */
        public data class Observed(val workspace: ResearchWorkspace, val isEnabled: Boolean) : Internal

        /** A new session or question was stored. */
        public data class Created(val workspace: ResearchWorkspace, val sessionId: String, val questionId: String) :
            Internal

        /** A source mutation completed. */
        public data object Mutated : Internal

        /** A new source was imported successfully. */
        public data object ResourceAdded : Internal

        /** A source/session mutation failed; the previous state remains usable. */
        public data object MutationFailed : Internal

        /** Initial load failed. */
        public data object LoadFailed : Internal

        /** A submitted prompt was durably accepted, so the UI can clear its draft. */
        public data class Submitted(val questionId: String) : Internal

        /** Native execution ended, including rejection before acceptance. */
        public data class RunFinished(val questionId: String, val isFailed: Boolean) : Internal
    }
}

/** IO requests executed by the profile repository. */
public sealed interface ResearchChatEffect : MachineEffect {
    /** Checks eligibility and reads or prepares the first session. */
    public data class Load(val target: EngineTarget) : ResearchChatEffect

    /** Observes profile data and the research switch while the workspace is open. */
    public data object Observe : ResearchChatEffect

    /** Creates a research session. */
    public data class CreateSession(val target: EngineTarget) : ResearchChatEffect

    /** Creates a question. */
    public data class CreateQuestion(val sessionId: String) : ResearchChatEffect

    /** Imports user input. */
    public data class AddResource(
        val sessionId: String,
        val questionId: String,
        val input: ResearchChatIntent.Public.AddResource,
    ) : ResearchChatEffect

    /** Updates a question's source selection. */
    public data class SelectResource(
        val sessionId: String,
        val questionId: String,
        val resourceId: String,
        val isSelected: Boolean,
    ) : ResearchChatEffect

    /** Promotes a local source to the shared catalog. */
    public data class ShareResource(val sessionId: String, val resourceId: String) : ResearchChatEffect

    /** Deletes a source. */
    public data class RemoveResource(val sessionId: String, val resourceId: String) : ResearchChatEffect

    /** Starts profile-owned generation and waits for its result. */
    public data class Run(val sessionId: String, val questionId: String, val prompt: String) : ResearchChatEffect {
        override fun toString(): String = "Run(sessionId=$sessionId, questionId=$questionId)"
    }

    /** Sends explicit native cancellation. */
    public data class Stop(val questionId: String) : ResearchChatEffect
}

/** One-off screen acknowledgements. */
public sealed interface ResearchChatOutput : MachineOutput {
    /** Clear only the draft of the accepted question, even if selection has changed. */
    public data class Submitted(val questionId: String) : ResearchChatOutput

    /** Clear resource input only after a successful import. */
    public data object ResourceAdded : ResearchChatOutput
}

/** Address of the feature-scoped research machine. */
public data object ResearchChatMachineKey : MachineKey<
    ResearchChatState,
    ResearchChatIntent,
    ResearchChatIntent.Public,
    ResearchChatEffect,
    ResearchChatOutput,
> {
    override val name: String = "ResearchChat"
}
