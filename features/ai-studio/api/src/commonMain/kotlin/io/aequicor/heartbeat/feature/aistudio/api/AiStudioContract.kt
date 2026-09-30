package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** AI studio workspace: projects, agent sessions and their conversations. Owned by the active profile. */
@Serializable
@SerialName("ai_studio")
public data object AiStudioRoute : Route

/**
 * Profile chats executed through the engine facade and the studio as the start screen of a profile.
 * Off (default): the in-memory demo workspace with the scripted agent and the welcome screen as the profile start.
 */
public val StudioEngineRuntime: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "ai_studio.engine_runtime",
    description = "AI-студия: чаты профиля через подключённые движки",
    default = false,
)

/** Adds local folders for Codex/Pi; disabling stops new folder registration, while saved project chats still work. */
public val StudioLocalProjects: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "ai_studio.local_projects",
    description = "Локальные проекты: выбор папки и работа Codex/Pi в ней",
)

/** Studio workflow: workspace loading, open panes, model preferences and running agent sessions. */
public sealed interface AiStudioState : MachineState {
    /** The component has not started the workflow yet. */
    public data object Idle : AiStudioState

    /** The workspace availability and defaults are being resolved. */
    public data object Loading : AiStudioState

    /** The workspace is switched off by its feature toggle; only the placeholder is shown. */
    public data object Disabled : AiStudioState

    /** The workspace could not be prepared and can be retried. */
    public data object LoadError : AiStudioState

    /**
     * The workspace is usable. [panes] are shown side by side (one on compact screens: the focused one).
     * [running] sessions have an active agent run; [stopping] ones were asked to stop and await its end.
     * Archived sessions leave their panes for the new-session page of [defaultProjectId].
     * [observedRunning] is the latest profile runtime snapshot; [answeredPermissions] are request ids already
     * answered by the user and hidden until the engine withdraws them.
     * [runStartedAt] contains known start times of current runs, including runs resumed by reopening the studio.
     * [isProjectAddingAvailable] gates folder selection; [addingProjectTo] owns the single active picker.
     * [projectErrorPane] identifies the pane whose last folder selection failed.
     */
    public data class Ready(
        val panes: List<StudioPane>,
        val focusedPaneId: Int,
        val settings: RunSettings,
        val defaultProjectId: String? = null,
        val running: Set<String> = emptySet(),
        val stopping: Set<String> = emptySet(),
        val stopFailures: Set<String> = emptySet(),
        val uncancellable: Set<String> = emptySet(),
        val permissions: List<StudioPermission> = emptyList(),
        val observedRunning: Set<String> = emptySet(),
        val answeredPermissions: Set<String> = emptySet(),
        val runStartedAt: Map<String, Instant> = emptyMap(),
        /** Last measured context by conversation; missing entries have no telemetry. */
        val contexts: Map<String, ContextUsage> = emptyMap(),
        /** Provider quotas keyed by the full studio model route. */
        val providerUsage: Map<String, ProviderUsageSnapshot> = emptyMap(),
        val isProjectAddingAvailable: Boolean = false,
        val addingProjectTo: Int? = null,
        val projectErrorPane: Int? = null,
        val configurations: Map<String, StudioSessionConfiguration> = emptyMap(),
    ) : AiStudioState {
        init {
            require(panes.isNotEmpty()) { "The workspace always shows at least one pane" }
            require(panes.any { it.id == focusedPaneId }) { "The focused pane must be open" }
        }
    }
}

/** User requests and effect results of the studio. */
public sealed interface AiStudioIntent : MachineIntent {
    /** Intents accepted from screens and other features. */
    public sealed interface Public : AiStudioIntent {
        /** Starts the workflow once. */
        public data object Start : Public

        /** Prepares the workspace again after a failure. */
        public data object Retry : Public

        /** Shows the new-session page for [projectId] in [paneId] (the focused pane when `null`). */
        public data class NewSession(val projectId: String?, val paneId: Int? = null) : Public

        /** Changes the project a new session of [paneId] will belong to. */
        public data class SelectProject(val paneId: Int, val projectId: String?) : Public

        /** Opens the local folder picker for a new-session pane; paths never enter machine state. */
        public data class AddProject(val paneId: Int) : Public

        /** Shows [sessionId] in [paneId] (the focused pane when `null`), or focuses the pane already showing it. */
        public data class OpenSession(val sessionId: String, val paneId: Int? = null) : Public

        /** Shows [sessionId] (or a new-session page when `null`) next to the focused pane. */
        public data class OpenBeside(val sessionId: String?) : Public

        /** Closes one of several panes. */
        public data class ClosePane(val paneId: Int) : Public

        /** Moves the keyboard and composer focus to another pane. */
        public data class FocusPane(val paneId: Int) : Public

        /** Replaces the model preferences for the next runs. */
        public data class UpdateSettings(val settings: RunSettings) : Public

        /** Observes quotas for the model routes currently visible in composer panes. */
        public data class ObserveUsageTargets(val modelIds: Set<String>) : Public

        /** Requests fresh provider quotas when the usage panel opens. */
        public data class RefreshUsage(val modelId: String) : Public

        /** Applies one parameter to [sessionId] without restarting its accepted work. */
        public data class ChangeSessionSetting(val sessionId: String, val change: StudioSettingChange) : Public

        /** Sends [prompt] from the composer of [paneId]: creates a session if needed and starts a run. */
        public data class Submit(val paneId: Int, val prompt: String) : Public

        /** Asks the running agent of [sessionId] to stop. */
        public data class Stop(val sessionId: String) : Public

        /**
         * Answers one currently pending engine permission with an offered option and, for permissions with input,
         * the structured [answer] (e.g. from the questionnaire).
         */
        public data class RespondPermission(
            val sessionId: String,
            val requestId: String,
            val optionId: String,
            val answer: StudioPermissionAnswer? = null,
        ) : Public

        /**
         * Sends [prompt] as the next user message of [sessionId] without a composer (e.g. an answer to a question
         * whose native request no longer exists); accepted only while the session is idle.
         */
        public data class FollowUp(val sessionId: String, val prompt: String) : Public

        /** Changes the metadata of [sessionId]. */
        public data class Edit(val sessionId: String, val edit: SessionEdit) : Public
    }

    /** Results produced by the studio effects. */
    public sealed interface Internal : AiStudioIntent {
        /** The workspace toggle and defaults are resolved. */
        public data class Loaded(val isEnabled: Boolean, val defaults: StudioDefaults) : Internal

        /** Snapshot of executions which survive closing the studio. */
        public data class RuntimeChanged(val snapshot: StudioRuntimeState) : Internal

        /** The workspace could not be prepared. */
        public data object LoadFailed : Internal

        /** The workspace toggle reports [isEnabled]; the first report repeats the current value. */
        public data class AvailabilityChanged(val isEnabled: Boolean) : Internal

        /** The prompt submitted from [paneId] created [sessionId]; its run starts now. */
        public data class SessionCreated(
            val paneId: Int,
            val sessionId: String,
            val prompt: String,
            val settings: RunSettings,
        ) : Internal

        /** The session for a prompt of [paneId] could not be created. */
        public data class CreateFailed(val paneId: Int, val prompt: String) : Internal

        /** Explicit stop failed; keep observing the native turn and permit another stop attempt. */
        public data class CancelFailed(val sessionId: String) : Internal

        /** The agent run of [sessionId] ended. */
        public data class RunFinished(val sessionId: String, val outcome: RunOutcome) : Internal

        /** Answering permission [requestId] failed; the request is shown again by the next runtime snapshot. */
        public data class PermissionAnswerFailed(val sessionId: String, val requestId: String) : Internal

        /** The runtime observation failed; nothing is known to run any more. */
        public data object RuntimeLost : Internal

        /** Route ids of the models currently offered, in display order. */
        public data class ModelsChanged(val modelIds: List<String>) : Internal

        /** Local project feature and platform availability changed. */
        public data class ProjectAvailabilityChanged(val isAvailable: Boolean) : Internal

        /** The picker registered a stable project id, or returned null when cancelled. */
        public data class ProjectChosen(val paneId: Int, val projectId: String?) : Internal

        /** Folder selection or registration failed; the pane remains usable and can retry. */
        public data class ProjectChoiceFailed(val paneId: Int) : Internal
    }
}

/** IO commands of the studio, executed by the feature's effect handler. */
public sealed interface AiStudioEffect : MachineEffect {
    /** Resolves the workspace toggle and defaults. */
    public data object Load : AiStudioEffect

    /** Reports changes of the workspace toggle for as long as the current state lasts. */
    public data object ObserveAvailability : AiStudioEffect

    /** Observes profile-owned execution and pending permissions. */
    public data object ObserveRuntime : AiStudioEffect

    /** Reports the offered models for as long as the workspace is ready. */
    public data object ObserveModels : AiStudioEffect

    /** Observes whether local project selection is available. */
    public data object ObserveProjects : AiStudioEffect

    /** Read-only usage commands; failure never changes conversation execution state. */
    public sealed interface Usage : AiStudioEffect

    /** Updates the model routes whose provider quotas the studio observes. */
    public data class ObserveUsageTargets(val modelIds: Set<String>) : Usage

    /** Refreshes provider quotas for one model route without generating a turn. */
    public data class RefreshUsage(val modelId: String) : Usage

    /** Picks and registers a local folder without logging or exposing its path to the machine. */
    public data class ChooseProject(val paneId: Int) : AiStudioEffect

    /** Sends an explicit engine-offered decision with the structured [answer] of its input. */
    public data class RespondPermission(
        val sessionId: String,
        val requestId: String,
        val optionId: String,
        val answer: StudioPermissionAnswer? = null,
    ) : AiStudioEffect

    /** Creates a session for the first [prompt] of [paneId] inside [projectId]. */
    public data class CreateSession(
        val paneId: Int,
        val projectId: String?,
        val prompt: String,
        val settings: RunSettings,
    ) : AiStudioEffect

    /** Records [prompt] and streams the agent reply into [sessionId] until it completes or is stopped. */
    public data class Run(val sessionId: String, val prompt: String, val settings: RunSettings) : AiStudioEffect

    /** Signals the run of [sessionId] to stop; the run itself reports its end. */
    public data class Cancel(val sessionId: String) : AiStudioEffect

    /** Persists a metadata [edit] of [sessionId]. */
    public data class Apply(val sessionId: String, val edit: SessionEdit) : AiStudioEffect

    /** Hands a configuration change to the profile; cancelling the screen waiter does not cancel it. */
    public data class ChangeSessionSetting(val sessionId: String, val change: StudioSettingChange) : AiStudioEffect
}

/** One-shot events of the studio. */
public sealed interface AiStudioOutput : MachineOutput {
    /** The prompt of [paneId] was not sent; the composer can restore it. */
    public data class SubmitFailed(val paneId: Int, val prompt: String) : AiStudioOutput

    /** An accepted answer to permission [requestId] of [sessionId] did not reach the engine. */
    public data class PermissionAnswerFailed(val sessionId: String, val requestId: String) : AiStudioOutput

    /** An effect run of [sessionId] ended with [outcome] (emitted for every `RunFinished`). */
    public data class RunEnded(val sessionId: String, val outcome: RunOutcome) : AiStudioOutput
}

/** Address of the studio machine. */
public object AiStudioMachineKey :
    MachineKey<AiStudioState, AiStudioIntent, AiStudioIntent.Public, AiStudioEffect, AiStudioOutput> {
    override val name: String = "ai_studio"
}
