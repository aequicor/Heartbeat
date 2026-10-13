@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ImmutableListSerializer
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ImmutableMapSerializer
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ImmutableSetSerializer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import kotlin.time.Instant

/** Screen phase derived from the studio machine. */
@Serializable
@SerialName("StudioPhase")
enum class StudioPhase { Loading, Ready, Disabled, Error }

/** Which list the sidebar shows. */
@Serializable
@SerialName("SidebarMode")
enum class SidebarMode { Workspace, Archive }

/** A project row of the sidebar. */
@Serializable
@SerialName("ProjectUi")
@Immutable
data class ProjectUi(val id: String, val name: String, val environment: EnvironmentUi, val branch: String)

/** A pending scheduler wake, distinct from a currently executing engine turn. */
@Serializable
@SerialName("SessionWaitUi")
enum class SessionWaitUi { Sleeping, WaitingForEvent }

/** A session row; execution and scheduled waits come from their machines, metadata from the repository. */
@Serializable
@SerialName("SessionUi")
@Immutable
data class SessionUi(
    val id: String,
    val title: String,
    val projectId: String?,
    val updatedAt: Instant,
    val isPinned: Boolean = false,
    val isUnread: Boolean = false,
    val isArchived: Boolean = false,
    val branch: String? = null,
    val isRunning: Boolean = false,
    val modelId: String? = null,
    val isContinuable: Boolean = true,
    val isWorktree: Boolean = false,
    /** An organic AI organism of the same id drives this chat; the chat only shows its sessions. */
    val isOrganism: Boolean = false,
    val isAwaitingChecklist: Boolean = false,
    val isReady: Boolean = false,
    val scheduledWait: SessionWaitUi? = null,
    val parentChatId: String? = null,
    /** Sidebar-only indentation, derived after filtering and grouping the visible helper tree. */
    val depth: Int = 0,
    val isAwaitingPermission: Boolean = false,
    /** A real parent exists outside the search filter; nested chats inherit that root's sidebar section. */
    val isNestedInSidebar: Boolean = false,
    /** Actual execution directory reference, which differs from [projectId] for worktree chats. */
    val executionWorkspace: String? = null,
) {
    /** Work is pending even when no engine turn is currently executing. */
    val isWaiting: Boolean get() = isAwaitingPermission || scheduledWait != null || isAwaitingChecklist
}

/** Title being edited inline; [origin] is the list row or pane header hosting the field. */
@Serializable
@SerialName("RenameUi")
@Immutable
data class RenameUi(val sessionId: String, val title: String, val origin: String)

/** Local sidebar presentation: none of it is business state. */
@Serializable
@SerialName("SidebarUi")
@Immutable
data class SidebarUi(
    val mode: SidebarMode = SidebarMode.Workspace,
    val query: String = "",
    val isSearchVisible: Boolean = false,
    val collapsedProjects: ImmutableSet<String> = persistentSetOf(),
    val isProjectsExpanded: Boolean = true,
    val isRecentExpanded: Boolean = true,
    val renaming: RenameUi? = null,
    val isVisible: Boolean = true,
    val isDrawerOpen: Boolean = false,
)

/**
 * Studio screen. [panes], [focusedPaneId], [running], [stopping] and [settings] mirror the machine;
 * [projects], [sessions] and [transcripts] (of open sessions) mirror the repository; [drafts] and [sidebar]
 * are local input. [runStartedAt] mirrors execution start times; [now] ticks while a run is active.
 */
@Serializable
@SerialName("AiStudioScreenState")
@Immutable
data class AiStudioScreenState(
    val models: ImmutableList<ModelUi> = persistentListOf(),
    val isResearchEnabled: Boolean = false,
    val isProjectAddingAvailable: Boolean = false,
    val isWorktreeAvailable: Boolean = false,
    val worktrees: ImmutableMap<String, WorktreeUi> = persistentMapOf(),
    val worktreeJournal: WorktreeJournalUi = WorktreeJournalUi.Ready,
    val addingProjectTo: Int? = null,
    val projectErrorPane: Int? = null,
    val permissions: ImmutableList<PermissionUi> = persistentListOf(),
    val phase: StudioPhase = StudioPhase.Loading,
    val panes: ImmutableList<PaneUi> = persistentListOf(),
    val focusedPaneId: Int = 0,
    val running: ImmutableSet<String> = persistentSetOf(),
    val contexts: ImmutableMap<String, ContextUsageUi> = persistentMapOf(),
    val providerUsage: ImmutableMap<String, ProviderUsageUi> = persistentMapOf(),
    val runStartedAt: ImmutableMap<String, Instant> = persistentMapOf(),
    val stopping: ImmutableSet<String> = persistentSetOf(),
    val stopFailures: ImmutableSet<String> = persistentSetOf(),
    val uncancellable: ImmutableSet<String> = persistentSetOf(),
    val settings: SettingsUi = DefaultSettingsUi,
    val configurations: ImmutableMap<String, SessionConfigurationUi> = persistentMapOf(),
    val projects: ImmutableList<ProjectUi> = persistentListOf(),
    val sessions: ImmutableList<SessionUi> = persistentListOf(),
    val transcripts: ImmutableMap<String, ImmutableList<MessageUi>> = persistentMapOf(),
    val drafts: ImmutableMap<String, String> = persistentMapOf(),
    /** Caret position of each composer draft; drives the active autocomplete token. */
    val draftCarets: ImmutableMap<String, Int> = persistentMapOf(),
    /** One-shot editor caret requests tied to accepted replacement text. */
    val draftCaretRequests: ImmutableMap<String, ComposerCaretRequestUi> = persistentMapOf(),
    /** Suggestions of each composer draft's active token; absent hides the popup. */
    val composerSuggestions: ImmutableMap<String, ComposerSuggestionsUi> = persistentMapOf(),
    val draftAttachments: ImmutableMap<String, ImmutableList<AttachmentUi>> = persistentMapOf(),
    val submissions: ImmutableMap<String, SubmissionUi> = persistentMapOf(),
    /** Diagram messages awaiting native acceptance, independent of composer snapshots. */
    val diagramSubmissions: ImmutableMap<String, DiagramSubmissionUi> = persistentMapOf(),
    val diagramFocusRequests: ImmutableMap<Int, Long> = persistentMapOf(),
    val diagramDraftReasons: ImmutableMap<String, DiagramDraftReasonUi> = persistentMapOf(),
    val attachmentRequests: ImmutableMap<String, String> = persistentMapOf(),
    val visibleAttachmentPreviews: ImmutableMap<String, PreviewVisibilityUi> = persistentMapOf(),
    val attachmentPreviews: ImmutableMap<String, AttachmentPreviewUi> = persistentMapOf(),
    val isAttachmentsEnabled: Boolean = false,
    /** Whether the composer offers the `/remember` command. */
    val isRememberEnabled: Boolean = false,
    /** Whether the composer offers `/compact` and the context indicator its compaction action. */
    val isCompactEnabled: Boolean = false,
    /** How the feed collapses agent work. */
    val feedCollapse: FeedCollapseUi = FeedCollapseUi(),
    /** Whether a new chat may be started as an organic AI organism. */
    val isOrganismEnabled: Boolean = false,
    /** Harnesses offered in the "+" menu, chat connections and choices of new chats. */
    val harnessChoices: HarnessChoicesUi = HarnessChoicesUi(),
    /** Organisms of organism chats, by chat id. */
    val organisms: ImmutableMap<String, OrganismUi> = persistentMapOf(),
    /** The sub-session each organism chat shows, by chat id; the zygote when absent. */
    val subSessions: ImmutableMap<String, String> = persistentMapOf(),
    /** Live transcripts of the shown sub-sessions of open organism chats, by chat id. */
    val subTranscripts: ImmutableMap<String, ImmutableList<MessageUi>> = persistentMapOf(),
    /** Telemetry belongs to the selected cell or judge, never to the organism as a whole. */
    val subObservations: ImmutableMap<String, SubSessionObservationUi> = persistentMapOf(),
    val nativeTrees: ImmutableMap<String, SessionTreeUi> = persistentMapOf(),
    val nativeTranscripts: ImmutableMap<String, NativeTranscriptUi> = persistentMapOf(),
    val attachmentErrorPanes: ImmutableSet<Int> = persistentSetOf(),
    val failedPanes: ImmutableSet<Int> = persistentSetOf(),
    val sidebar: SidebarUi = SidebarUi(),
    val now: Instant = Instant.DISTANT_PAST,
) : MVIState {
    /** The session of [id] with its running flag. */
    fun session(id: String?): SessionUi? =
        sessions.firstOrNull { it.id == id }?.let { it.copy(isRunning = it.id in running) }

    /** The project of [id]. */
    fun project(id: String?): ProjectUi? = projects.firstOrNull { it.id == id }

    /** Composer text of [paneId]: a draft follows the session shown in the pane; a new-session page keeps its own. */
    fun draft(paneId: Int): String = drafts[draftKey(paneId)].orEmpty()

    /** Key of the draft pane [paneId] edits: its displayed submission, its chat or the pane itself. */
    fun draftKey(paneId: Int): String {
        val sessionId = panes.firstOrNull { it.id == paneId }?.sessionId
        return submissions.values.firstOrNull {
            it.paneId == paneId && it.isDisplayed
        }?.draftKey ?: (sessionId ?: "pane:$paneId")
    }

    /** Durable metadata of files in the transient composer draft. */
    fun attachments(paneId: Int): ImmutableList<AttachmentUi> = draftAttachments[draftKey(paneId)] ?: persistentListOf()

    /** Suggestions of the pane's active token; null while no token is being completed. */
    fun suggestions(paneId: Int): ComposerSuggestionsUi? = composerSuggestions[draftKey(paneId)]

    /** The caret of the pane's draft; end of text until the editor reports a position. */
    fun draftCaret(paneId: Int): Int = draftCarets[draftKey(paneId)] ?: draft(paneId).length

    /** Last accepted completion's caret request for the pane's editor. */
    fun caretRequest(paneId: Int): ComposerCaretRequestUi? = draftCaretRequests[draftKey(paneId)]
}

/** Apply this caret once, after the editor displays [text]; [id] is unique across this screen's drafts. */
@Serializable
@SerialName("ComposerCaretRequestUi")
@Immutable
data class ComposerCaretRequestUi(val id: Long, val text: String, val caret: Int)

/** User events of the studio screen. */
@Serializable
@SerialName("AiStudioScreenIntent")
sealed interface AiStudioScreenIntent : MVIIntent {
    /** Changes what the panes show; forwarded to the machine. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Navigation")
    sealed interface Navigation : AiStudioScreenIntent

    /** Explicit reply to an engine permission request. */
    @Serializable
    @SerialName("AiStudioScreenIntent.RespondPermission")
    data class RespondPermission(val sessionId: String, val requestId: String, val optionId: String) : Composer

    /** Composer input, runs and model preferences. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Composer")
    sealed interface Composer : AiStudioScreenIntent

    /** Connects a harness to the chat of [paneId], or remembers the choice for a new chat. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectHarness")
    data class SelectHarness(val paneId: Int, val harness: String, val isSelected: Boolean) : AiStudioScreenIntent

    /** Organic AI chats: the mode of a new chat, the shown sub-session, control and the user's decisions. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Organism")
    sealed interface Organism : AiStudioScreenIntent

    /** Switches the organic AI mode of a new chat in [paneId]. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectOrganism")
    data class SelectOrganism(val paneId: Int, val isEnabled: Boolean) : Organism

    /** Shows the sub-session [key] (a cell id or a case id) of the organism chat [sessionId]. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectSubSession")
    data class SelectSubSession(val sessionId: String, val key: String) : Organism

    /** Aborts the organism of [sessionId] or resumes its stalled zygote. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ControlOrganism")
    data class ControlOrganism(val sessionId: String, val action: OrganismActionUi) : Organism

    /** Answers the permission request [requestId] of the [turn] of [cell] in the organism of [sessionId]. */
    @Serializable
    @SerialName("AiStudioScreenIntent.DecideOrganism")
    data class DecideOrganism(
        val sessionId: String,
        val cell: String,
        val turn: String,
        val requestId: String,
        val optionId: String,
    ) : Organism

    /** Local attachment input and saved-file actions. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Attachment")
    sealed interface Attachment : Composer

    /** Worktree execution mode and the persisted task's completion/build controls. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Worktree")
    sealed interface Worktree : Composer

    /** Metadata changes of one session. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SessionAction")
    sealed interface SessionAction : AiStudioScreenIntent

    /** Local sidebar presentation. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Sidebar")
    sealed interface Sidebar : AiStudioScreenIntent

    /** Prepares the workspace again after a failure. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Retry")
    data object Retry : Navigation

    /** Opens the new-session page for [projectId] in the focused pane. */
    @Serializable
    @SerialName("AiStudioScreenIntent.NewSession")
    data class NewSession(val projectId: String?) : Navigation

    /** Changes the project of a new session. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectProject")
    data class SelectProject(val paneId: Int, val projectId: String?) : Navigation

    /** Chooses and registers a local project for a new chat. */
    @Serializable
    @SerialName("AiStudioScreenIntent.AddProject")
    data class AddProject(val paneId: Int) : Navigation

    /** Shows a session in the focused pane. */
    @Serializable
    @SerialName("AiStudioScreenIntent.OpenSession")
    data class OpenSession(val sessionId: String) : Navigation

    /** Shows a session (or a new-session page) next to the focused pane. */
    @Serializable
    @SerialName("AiStudioScreenIntent.OpenBeside")
    data class OpenBeside(val sessionId: String?) : Navigation

    /** Closes one of several panes. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ClosePane")
    data class ClosePane(val paneId: Int) : Navigation

    /** Moves the composer focus to another pane. */
    @Serializable
    @SerialName("AiStudioScreenIntent.FocusPane")
    data class FocusPane(val paneId: Int) : Navigation

    /** Composer autocomplete: draft and caret changes, navigation, acceptance and dismissal. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Suggestions")
    sealed interface Suggestions : Composer {
        /** The composer text of a pane changed. */
        @Serializable
        @SerialName("AiStudioScreenIntent.Suggestions.DraftChanged")
        data class DraftChanged(val paneId: Int, val text: String) : Suggestions

        /** The caret of a pane's composer moved; the active token is resolved against it. */
        @Serializable
        @SerialName("AiStudioScreenIntent.Suggestions.CaretMoved")
        data class CaretMoved(val paneId: Int, val caret: Int) : Suggestions

        /** The editor applied this one-shot caret request; remounting must not replay it. */
        @Serializable
        @SerialName("AiStudioScreenIntent.Suggestions.CaretRequestApplied")
        data class CaretRequestApplied(val paneId: Int, val id: Long) : Suggestions

        /** Keyboard navigation moved the suggestion selection by [delta] within the pane's list. */
        @Serializable
        @SerialName("AiStudioScreenIntent.Suggestions.MoveSuggestion")
        data class MoveSuggestion(val paneId: Int, val delta: Int) : Suggestions

        /** Takes the suggestion at [index] of the pane's list, or the selected one when null. */
        @Serializable
        @SerialName("AiStudioScreenIntent.Suggestions.AcceptSuggestion")
        data class AcceptSuggestion(val paneId: Int, val index: Int? = null) : Suggestions

        /** Hides the pane's suggestion list until the active token changes. */
        @Serializable
        @SerialName("AiStudioScreenIntent.Suggestions.DismissSuggestions")
        data class DismissSuggestions(val paneId: Int) : Suggestions
    }

    /** Adds files using a lifecycle-owned native picker. */
    @Serializable
    @SerialName("AiStudioScreenIntent.PickAttachments")
    data class PickAttachments(val paneId: Int) : Attachment

    /** Removes a file from the transient draft without deleting its durable copy. */
    @Serializable
    @SerialName("AiStudioScreenIntent.RemoveAttachment")
    data class RemoveAttachment(val paneId: Int, val id: String) : Attachment

    /** Opens a saved file for preview and export, including with adding switched off. */
    @Serializable
    @SerialName("AiStudioScreenIntent.OpenAttachment")
    data class OpenAttachment(val id: String) : Attachment

    /** Saves original bytes through the saved attachment's native export route. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ExportAttachment")
    data class ExportAttachment(val id: String) : Attachment

    /** Imports explicitly captured clipboard/drop inputs. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ImportAttachments")
    data class ImportAttachments(val paneId: Int, val inputs: List<NativeAttachmentUi>) : Attachment {
        override fun toString(): String = "ImportAttachments(paneId=$paneId, count=${inputs.size})"
    }

    /** Correlated picker result delivered by the navigation component. */
    @Serializable
    @SerialName("AiStudioScreenIntent.AttachmentsSelected")
    data class AttachmentsSelected(val requestId: String, val files: ImmutableList<AttachmentUi>) : Attachment

    /** Composed attachment rows request their small preview and release it on disposal. */
    @Serializable
    @SerialName("AiStudioScreenIntent.AttachmentPreviewVisible")
    data class AttachmentPreviewVisible(val id: String, val mediaType: String, val isVisible: Boolean) : Attachment

    /** Fixes the execution mode of a new pane before its first submission. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectWorktree")
    data class SelectWorktree(val paneId: Int, val isEnabled: Boolean) : Worktree

    /** Applies one explicit decision to the saved result. */
    @Serializable
    @SerialName("AiStudioScreenIntent.DecideWorktree")
    data class DecideWorktree(val sessionId: String, val action: WorktreeActionUi) : Worktree

    /** Reconciles interrupted operations against the current checkout. */
    @Serializable
    @SerialName("AiStudioScreenIntent.RecheckWorktree")
    data class RecheckWorktree(val sessionId: String) : Worktree

    /** Restores the profile journal after a storage failure. */
    @Serializable
    @SerialName("AiStudioScreenIntent.RetryWorktreeJournal")
    data object RetryWorktreeJournal : Worktree

    /** Cancels one queued or running coordinated build. */
    @Serializable
    @SerialName("AiStudioScreenIntent.CancelWorktreeBuild")
    data class CancelWorktreeBuild(val sessionId: String, val operation: String) : Worktree

    /** A user clicked an action in the editable root transcript of this session. */
    @Serializable
    @SerialName("AiStudioScreenIntent.DiagramAction")
    data class DiagramAction(val sessionId: String, val text: String, val isSend: Boolean) : AiStudioScreenIntent {
        override fun toString(): String = "DiagramAction(sessionId=$sessionId, isSend=$isSend)"
    }

    /** Sends the composer text of a pane. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Submit")
    data class Submit(val paneId: Int) : Composer

    /** Stops the running agent of a session. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Stop")
    data class Stop(val sessionId: String) : Composer

    /** Actions of the context and limits indicator. */
    @Serializable
    @SerialName("AiStudioScreenIntent.Usage")
    sealed interface Usage : Composer

    /** Requests provider telemetry when the details panel opens. */
    @Serializable
    @SerialName("AiStudioScreenIntent.RefreshUsage")
    data class RefreshUsage(val modelId: String) : Usage

    /** Compacts the context of the pane's chat once it is idle. */
    @Serializable
    @SerialName("AiStudioScreenIntent.CompactContext")
    data class CompactContext(val paneId: Int) : Usage

    /** Changes an existing session's model, or the default for a new page. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectModel")
    data class SelectModel(val modelId: String, val paneId: Int? = null) : Composer

    /** Changes the session's effort for subsequent requests, or the default for a new page. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectEffort")
    data class SelectEffort(val effort: EffortUi, val paneId: Int? = null) : Composer

    /** Selects an advertised native effort for an exact model route; null restores the engine default. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectEngineEffort")
    data class SelectEngineEffort(val modelId: String, val effort: String?, val paneId: Int? = null) : Composer

    /** Chooses how the agent treats actions with side effects. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SelectApproval")
    data class SelectApproval(val approval: ApprovalUi, val paneId: Int? = null) : Composer

    /** Pins or unpins a session. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SetPinned")
    data class SetPinned(val sessionId: String, val isPinned: Boolean) : SessionAction

    /** Marks a session as read or unread. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SetUnread")
    data class SetUnread(val sessionId: String, val isUnread: Boolean) : SessionAction

    /** Archives or restores a session. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SetArchived")
    data class SetArchived(val sessionId: String, val isArchived: Boolean) : SessionAction

    /** Starts editing the title of a session in the sidebar. */
    @Serializable
    @SerialName("AiStudioScreenIntent.StartRename")
    data class StartRename(val sessionId: String, val origin: String) : SessionAction

    /** The edited title changed. */
    @Serializable
    @SerialName("AiStudioScreenIntent.RenameChanged")
    data class RenameChanged(val title: String) : SessionAction

    /** Saves the edited title. */
    @Serializable
    @SerialName("AiStudioScreenIntent.CommitRename")
    data object CommitRename : SessionAction

    /** Discards the edited title. */
    @Serializable
    @SerialName("AiStudioScreenIntent.CancelRename")
    data object CancelRename : SessionAction

    /** Switches between active and archived sessions. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ShowSidebarMode")
    data class ShowSidebarMode(val mode: SidebarMode) : Sidebar

    /** Shows or hides the sidebar search field; hiding it clears the query. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ToggleSearch")
    data object ToggleSearch : Sidebar

    /** The sidebar search query changed. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SearchChanged")
    data class SearchChanged(val query: String) : Sidebar

    /** Expands or collapses the sessions of a project. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ToggleProject")
    data class ToggleProject(val projectId: String) : Sidebar

    /** Expands or collapses the projects section. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ToggleProjectsSection")
    data object ToggleProjectsSection : Sidebar

    /** Expands or collapses the recent sessions section. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ToggleRecentSection")
    data object ToggleRecentSection : Sidebar

    /** Shows or hides the sidebar on wide windows. */
    @Serializable
    @SerialName("AiStudioScreenIntent.ToggleSidebar")
    data object ToggleSidebar : Sidebar

    /** Opens or closes the sidebar drawer on compact windows. */
    @Serializable
    @SerialName("AiStudioScreenIntent.SetDrawerOpen")
    data class SetDrawerOpen(val isOpen: Boolean) : Sidebar
}

/** No one-shot screen actions: every reaction is state. */
@Serializable
@SerialName("AiStudioScreenAction")
sealed interface AiStudioScreenAction : MVIAction

/** What a suggestion completes; the section label and icon derive from kind and origin. */
@Serializable
@SerialName("SuggestionKindUi")
enum class SuggestionKindUi { Command, Skill, File }

/** Which parts of answers the feed hides (context settings): reasoning, tool calls. */
@Serializable
@SerialName("FeedCollapseUi")
@Immutable
data class FeedCollapseUi(val isReasoningCollapsed: Boolean = false, val isToolCallsCollapsed: Boolean = false)

/** Where a suggestion comes from: Heartbeat itself or a native engine (its engine id). */
@Serializable
@SerialName("SuggestionOriginUi")
@Immutable
data class SuggestionOriginUi(val engine: String?) {
    val isHeartbeat: Boolean get() = engine == null

    /** Named origins of the screen projection. */
    companion object {
        val Heartbeat = SuggestionOriginUi(null)
    }
}

/** One row of the composer suggestion popup; the API suggestion projected for the screen. */
@Serializable
@SerialName("SuggestionUi")
@Immutable
data class SuggestionUi(
    val id: String,
    val label: String,
    val description: String,
    val kind: SuggestionKindUi,
    val origin: SuggestionOriginUi,
    /** Text replacing the active token; empty for files, which complete from their path. */
    val insert: String,
    /** File suggestions the current model cannot take stay visible, greyed. */
    val isSupported: Boolean = true,
    val relativePath: String? = null,
    val location: String? = null,
    val sizeBytes: Long = 0,
    val mediaType: String? = null,
)

/** Suggestions of one pane's active composer token; [selectedIndex] follows keyboard navigation. */
@Serializable
@SerialName("ComposerSuggestionsUi")
@Immutable
data class ComposerSuggestionsUi(
    val items: ImmutableList<SuggestionUi>,
    val selectedIndex: Int,
    /** Token the list belongs to; Escape hides it until the token changes. */
    val token: String,
    /** Coordinator generation; acceptance and publication reject obsolete requests. */
    val requestId: Long = 0,
)

/** One actionable permission exposed by the current native session. */
@Serializable
@SerialName("PermissionUi")
@Immutable
data class PermissionUi(
    val sessionId: String,
    val requestId: String,
    val title: String,
    val options: ImmutableList<PermissionOptionUi>,
    /** What exactly is being approved, e.g. the text of an instruction the agent wants to remember. */
    val description: String? = null,
)

/** Exact native choice identity, displayed without inventing approval policy. */
@Serializable
@SerialName("PermissionOptionUi")
@Immutable
data class PermissionOptionUi(val id: String, val title: String)
