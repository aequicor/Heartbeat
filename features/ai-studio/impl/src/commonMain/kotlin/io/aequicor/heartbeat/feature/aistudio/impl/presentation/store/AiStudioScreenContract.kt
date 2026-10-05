package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import kotlin.time.Instant

/** Screen phase derived from the studio machine. */
enum class StudioPhase { Loading, Ready, Disabled, Error }

/** Which list the sidebar shows. */
enum class SidebarMode { Workspace, Archive }

/** A project row of the sidebar. */
@Immutable
data class ProjectUi(val id: String, val name: String, val environment: EnvironmentUi, val branch: String)

/** A session row; [isRunning] comes from the machine, everything else from the repository. */
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
)

/** Title being edited inline; [origin] is the list row or pane header hosting the field. */
@Immutable
data class RenameUi(val sessionId: String, val title: String, val origin: String)

/** Local sidebar presentation: none of it is business state. */
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
    val draftAttachments: ImmutableMap<String, ImmutableList<AttachmentUi>> = persistentMapOf(),
    val submissions: ImmutableMap<String, SubmissionUi> = persistentMapOf(),
    val attachmentRequests: ImmutableMap<String, String> = persistentMapOf(),
    val visibleAttachmentPreviews: ImmutableMap<String, PreviewVisibilityUi> = persistentMapOf(),
    val attachmentPreviews: ImmutableMap<String, AttachmentPreviewUi> = persistentMapOf(),
    val isAttachmentsEnabled: Boolean = false,
    /** Whether the composer offers the `/remember` command. */
    val isRememberEnabled: Boolean = false,
    /** Whether a new chat may be started as an organic AI organism. */
    val isOrganismEnabled: Boolean = false,
    /** Organisms of organism chats, by chat id. */
    val organisms: ImmutableMap<String, OrganismUi> = persistentMapOf(),
    /** The sub-session each organism chat shows, by chat id; the zygote when absent. */
    val subSessions: ImmutableMap<String, String> = persistentMapOf(),
    /** Live transcripts of the shown sub-sessions of open organism chats, by chat id. */
    val subTranscripts: ImmutableMap<String, ImmutableList<MessageUi>> = persistentMapOf(),
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

    internal fun draftKey(paneId: Int): String {
        val sessionId = panes.firstOrNull { it.id == paneId }?.sessionId
        return submissions.values.firstOrNull {
            it.paneId == paneId && it.isDisplayed
        }?.draftKey ?: (sessionId ?: "pane:$paneId")
    }

    /** Durable metadata of files in the transient composer draft. */
    fun attachments(paneId: Int): ImmutableList<AttachmentUi> = draftAttachments[draftKey(paneId)] ?: persistentListOf()
}

/** User events of the studio screen. */
sealed interface AiStudioScreenIntent : MVIIntent {
    /** Changes what the panes show; forwarded to the machine. */
    sealed interface Navigation : AiStudioScreenIntent

    /** Explicit reply to an engine permission request. */
    data class RespondPermission(val sessionId: String, val requestId: String, val optionId: String) : Composer

    /** Composer input, runs and model preferences. */
    sealed interface Composer : AiStudioScreenIntent

    /** Organic AI chats: the mode of a new chat, the shown sub-session, control and the user's decisions. */
    sealed interface Organism : AiStudioScreenIntent

    /** Switches the organic AI mode of a new chat in [paneId]. */
    data class SelectOrganism(val paneId: Int, val isEnabled: Boolean) : Organism

    /** Shows the sub-session [key] (a cell id or a case id) of the organism chat [sessionId]. */
    data class SelectSubSession(val sessionId: String, val key: String) : Organism

    /** Aborts the organism of [sessionId] or resumes its stalled zygote. */
    data class ControlOrganism(val sessionId: String, val action: OrganismActionUi) : Organism

    /** Answers the permission request [requestId] of the [turn] of [cell] in the organism of [sessionId]. */
    data class DecideOrganism(
        val sessionId: String,
        val cell: String,
        val turn: String,
        val requestId: String,
        val optionId: String,
    ) : Organism

    /** Local attachment input and saved-file actions. */
    sealed interface Attachment : Composer

    /** Worktree execution mode and the persisted task's completion/build controls. */
    sealed interface Worktree : Composer

    /** Metadata changes of one session. */
    sealed interface SessionAction : AiStudioScreenIntent

    /** Local sidebar presentation. */
    sealed interface Sidebar : AiStudioScreenIntent

    /** Prepares the workspace again after a failure. */
    data object Retry : Navigation

    /** Opens the new-session page for [projectId] in the focused pane. */
    data class NewSession(val projectId: String?) : Navigation

    /** Changes the project of a new session. */
    data class SelectProject(val paneId: Int, val projectId: String?) : Navigation

    /** Chooses and registers a local project for a new chat. */
    data class AddProject(val paneId: Int) : Navigation

    /** Shows a session in the focused pane. */
    data class OpenSession(val sessionId: String) : Navigation

    /** Shows a session (or a new-session page) next to the focused pane. */
    data class OpenBeside(val sessionId: String?) : Navigation

    /** Closes one of several panes. */
    data class ClosePane(val paneId: Int) : Navigation

    /** Moves the composer focus to another pane. */
    data class FocusPane(val paneId: Int) : Navigation

    /** The composer text of a pane changed. */
    data class DraftChanged(val paneId: Int, val text: String) : Composer

    /** Adds files using a lifecycle-owned native picker. */
    data class PickAttachments(val paneId: Int) : Attachment

    /** Removes a file from the transient draft without deleting its durable copy. */
    data class RemoveAttachment(val paneId: Int, val id: String) : Attachment

    /** Opens a saved file for preview and export, including with adding switched off. */
    data class OpenAttachment(val id: String) : Attachment

    /** Saves original bytes through the saved attachment's native export route. */
    data class ExportAttachment(val id: String) : Attachment

    /** Imports explicitly captured clipboard/drop inputs. */
    data class ImportAttachments(val paneId: Int, val inputs: List<NativeAttachmentUi>) : Attachment {
        override fun toString(): String = "ImportAttachments(paneId=$paneId, count=${inputs.size})"
    }

    /** Correlated picker result delivered by the navigation component. */
    data class AttachmentsSelected(val requestId: String, val files: ImmutableList<AttachmentUi>) : Attachment

    /** Composed attachment rows request their small preview and release it on disposal. */
    data class AttachmentPreviewVisible(val id: String, val mediaType: String, val isVisible: Boolean) : Attachment

    /** Fixes the execution mode of a new pane before its first submission. */
    data class SelectWorktree(val paneId: Int, val isEnabled: Boolean) : Worktree

    /** Applies one explicit decision to the saved result. */
    data class DecideWorktree(val sessionId: String, val action: WorktreeActionUi) : Worktree

    /** Reconciles interrupted operations against the current checkout. */
    data class RecheckWorktree(val sessionId: String) : Worktree

    /** Restores the profile journal after a storage failure. */
    data object RetryWorktreeJournal : Worktree

    /** Cancels one queued or running coordinated build. */
    data class CancelWorktreeBuild(val sessionId: String, val operation: String) : Worktree

    /** Sends the composer text of a pane. */
    data class Submit(val paneId: Int) : Composer

    /** Stops the running agent of a session. */
    data class Stop(val sessionId: String) : Composer

    /** Requests provider telemetry when the details panel opens. */
    data class RefreshUsage(val modelId: String) : Composer

    /** Changes an existing session's model, or the default for a new page. */
    data class SelectModel(val modelId: String, val paneId: Int? = null) : Composer

    /** Changes the session's effort for subsequent requests, or the default for a new page. */
    data class SelectEffort(val effort: EffortUi, val paneId: Int? = null) : Composer

    /** Selects an advertised native effort for an exact model route; null restores the engine default. */
    data class SelectEngineEffort(val modelId: String, val effort: String?, val paneId: Int? = null) : Composer

    /** Chooses how the agent treats actions with side effects. */
    data class SelectApproval(val approval: ApprovalUi, val paneId: Int? = null) : Composer

    /** Pins or unpins a session. */
    data class SetPinned(val sessionId: String, val isPinned: Boolean) : SessionAction

    /** Marks a session as read or unread. */
    data class SetUnread(val sessionId: String, val isUnread: Boolean) : SessionAction

    /** Archives or restores a session. */
    data class SetArchived(val sessionId: String, val isArchived: Boolean) : SessionAction

    /** Starts editing the title of a session in the sidebar. */
    data class StartRename(val sessionId: String, val origin: String) : SessionAction

    /** The edited title changed. */
    data class RenameChanged(val title: String) : SessionAction

    /** Saves the edited title. */
    data object CommitRename : SessionAction

    /** Discards the edited title. */
    data object CancelRename : SessionAction

    /** Switches between active and archived sessions. */
    data class ShowSidebarMode(val mode: SidebarMode) : Sidebar

    /** Shows or hides the sidebar search field; hiding it clears the query. */
    data object ToggleSearch : Sidebar

    /** The sidebar search query changed. */
    data class SearchChanged(val query: String) : Sidebar

    /** Expands or collapses the sessions of a project. */
    data class ToggleProject(val projectId: String) : Sidebar

    /** Expands or collapses the projects section. */
    data object ToggleProjectsSection : Sidebar

    /** Expands or collapses the recent sessions section. */
    data object ToggleRecentSection : Sidebar

    /** Shows or hides the sidebar on wide windows. */
    data object ToggleSidebar : Sidebar

    /** Opens or closes the sidebar drawer on compact windows. */
    data class SetDrawerOpen(val isOpen: Boolean) : Sidebar
}

/** No one-shot screen actions: every reaction is state. */
sealed interface AiStudioScreenAction : MVIAction

/** One actionable permission exposed by the current native session. */
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
@Immutable
data class PermissionOptionUi(val id: String, val title: String)
