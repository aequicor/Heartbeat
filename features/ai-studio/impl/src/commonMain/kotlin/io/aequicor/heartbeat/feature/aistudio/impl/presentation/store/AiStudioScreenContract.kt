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
 * are local input. [now] ticks while a run is active, for elapsed-time labels.
 */
@Immutable
data class AiStudioScreenState(
    val phase: StudioPhase = StudioPhase.Loading,
    val panes: ImmutableList<PaneUi> = persistentListOf(),
    val focusedPaneId: Int = 0,
    val running: ImmutableSet<String> = persistentSetOf(),
    val stopping: ImmutableSet<String> = persistentSetOf(),
    val settings: SettingsUi = DefaultSettingsUi,
    val projects: ImmutableList<ProjectUi> = persistentListOf(),
    val sessions: ImmutableList<SessionUi> = persistentListOf(),
    val transcripts: ImmutableMap<String, ImmutableList<MessageUi>> = persistentMapOf(),
    val drafts: ImmutableMap<String, String> = persistentMapOf(),
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

    internal fun draftKey(paneId: Int): String = panes.firstOrNull { it.id == paneId }?.sessionId ?: "pane:$paneId"
}

/** User events of the studio screen. */
sealed interface AiStudioScreenIntent : MVIIntent {
    /** Changes what the panes show; forwarded to the machine. */
    sealed interface Navigation : AiStudioScreenIntent

    /** Composer input, runs and model preferences. */
    sealed interface Composer : AiStudioScreenIntent

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

    /** Sends the composer text of a pane. */
    data class Submit(val paneId: Int) : Composer

    /** Stops the running agent of a session. */
    data class Stop(val sessionId: String) : Composer

    /** Chooses the model of the next runs. */
    data class SelectModel(val modelId: String) : Composer

    /** Chooses the reasoning effort of the next runs. */
    data class SelectEffort(val effort: EffortUi) : Composer

    /** Chooses how the agent treats actions with side effects. */
    data class SelectApproval(val approval: ApprovalUi) : Composer

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
