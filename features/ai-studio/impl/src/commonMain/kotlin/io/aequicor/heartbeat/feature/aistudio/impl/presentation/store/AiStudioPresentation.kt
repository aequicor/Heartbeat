package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet

/** An empty draft is forgotten; editing clears a previous send failure. */
internal fun AiStudioScreenState.withDraft(paneId: Int, text: String): AiStudioScreenState = copy(
    diagramDraftReasons = (diagramDraftReasons - draftKey(paneId)).toImmutableMap(),
    drafts = draftKey(paneId).let { key -> if (text.isEmpty()) drafts - key else drafts + (key to text) }
        .toImmutableMap(),
    failedPanes = (failedPanes - paneId).toImmutableSet(),
)

/** Local follow-up of an accepted navigation: the drawer closes, closed panes forget their drafts. */
internal fun AiStudioScreenState.afterNavigation(
    intent: AiStudioScreenIntent.Navigation,
    replacedPanes: Set<Int> = when (intent) {
        is AiStudioScreenIntent.NewSession, is AiStudioScreenIntent.OpenSession -> setOf(focusedPaneId)

        is AiStudioScreenIntent.OpenBeside, is AiStudioScreenIntent.ClosePane, is AiStudioScreenIntent.FocusPane,
        is AiStudioScreenIntent.SelectProject, is AiStudioScreenIntent.AddProject, AiStudioScreenIntent.Retry,
        -> emptySet()
    },
): AiStudioScreenState = when (intent) {
    is AiStudioScreenIntent.NewSession, is AiStudioScreenIntent.OpenSession, is AiStudioScreenIntent.OpenBeside ->
        copy(
            sidebar = sidebar.copy(isDrawerOpen = false),
            submissions = submissions.mapValues { (_, pending) ->
                if (pending.paneId in replacedPanes) pending.copy(isDisplayed = false) else pending
            }.toImmutableMap(),
        )

    is AiStudioScreenIntent.ClosePane -> copy(
        drafts = (drafts - "pane:${intent.paneId}").toImmutableMap(),
        draftAttachments = (draftAttachments - "pane:${intent.paneId}").toImmutableMap(),
        submissions = submissions.mapValues { (_, pending) ->
            if (pending.paneId == intent.paneId) pending.copy(isDisplayed = false) else pending
        }.toImmutableMap(),
        failedPanes = (failedPanes - intent.paneId).toImmutableSet(),
    )

    AiStudioScreenIntent.Retry, is AiStudioScreenIntent.SelectProject, is AiStudioScreenIntent.FocusPane,
    is AiStudioScreenIntent.AddProject,
    -> this
}
internal fun AiStudioScreenState.startRename(sessionId: String, origin: String): AiStudioScreenState {
    val title = session(sessionId)?.title ?: return this
    return copy(sidebar = sidebar.copy(renaming = RenameUi(sessionId, title, origin)))
}
internal fun SidebarUi.reduce(intent: AiStudioScreenIntent.Sidebar): SidebarUi = when (intent) {
    is AiStudioScreenIntent.ShowSidebarMode -> copy(mode = intent.mode, isVisible = true, renaming = null)

    AiStudioScreenIntent.ToggleSearch -> copy(isSearchVisible = !isSearchVisible, query = "", isVisible = true)

    is AiStudioScreenIntent.SearchChanged -> copy(query = intent.query)

    is AiStudioScreenIntent.ToggleProject -> copy(
        collapsedProjects = if (intent.projectId in collapsedProjects) {
            collapsedProjects - intent.projectId
        } else {
            collapsedProjects + intent.projectId
        }.toImmutableSet(),
    )

    AiStudioScreenIntent.ToggleProjectsSection -> copy(isProjectsExpanded = !isProjectsExpanded)

    AiStudioScreenIntent.ToggleRecentSection -> copy(isRecentExpanded = !isRecentExpanded)

    AiStudioScreenIntent.ToggleSidebar -> copy(isVisible = !isVisible)

    is AiStudioScreenIntent.SetDrawerOpen -> copy(isDrawerOpen = intent.isOpen)
}

/** Unpinned sessions of one project, newest first; collapsed groups retain their hidden contents. */
@Immutable
data class ProjectGroupUi(val project: ProjectUi, val sessions: ImmutableList<SessionUi>, val isExpanded: Boolean)

/**
 * Sidebar lists, newest first within each group. Without a query, each active session is rendered once:
 * Root chats select their section: pinned, expanded project, then [recent]. Helper descendants follow their root.
 * Collapsing a project or the projects section returns its unpinned sessions to [recent].
 * The renderer controls visibility of the recent section without changing its contents.
 * With a query: [results] only. Archived sessions are listed separately.
 */
@Immutable
data class SidebarContent(
    val pinned: ImmutableList<SessionUi> = persistentListOf(),
    val projects: ImmutableList<ProjectGroupUi> = persistentListOf(),
    val recent: ImmutableList<SessionUi> = persistentListOf(),
    val results: ImmutableList<SessionUi>? = null,
    val archived: ImmutableList<SessionUi> = persistentListOf(),
)

/** Groups the sessions of [state] for the sidebar. */
internal fun sidebarContent(state: AiStudioScreenState): SidebarContent = sidebarContent(
    state.projects,
    state.sessions,
    state.running,
    state.sidebar,
    state.permissions.map { it.sessionId }.toSet(),
)

/** Groups [sessions] using running flags from [running] and visible list precedence from [sidebar]. */
fun sidebarContent(
    projects: List<ProjectUi>,
    sessions: List<SessionUi>,
    running: Set<String>,
    sidebar: SidebarUi,
    awaitingPermission: Set<String> = emptySet(),
): SidebarContent {
    val ordered = sessions
        .map { it.copy(isRunning = it.id in running, isAwaitingPermission = it.id in awaitingPermission) }
        .sortedByDescending { it.updatedAt }
    val active = SidebarSessionTree(ordered.filterNot { it.isArchived }).all()
    val archived = SidebarSessionTree(ordered.filter { it.isArchived }).all().toImmutableList()
    val query = sidebar.query.trim()
    if (query.isNotEmpty()) {
        val source = if (sidebar.mode == SidebarMode.Archive) archived else active
        return SidebarContent(
            results = SidebarSessionTree(
                source.filter { it.title.contains(query, ignoreCase = true) }.sortedByDescending { it.updatedAt },
            ).all().toImmutableList(),
            archived = archived,
        )
    }
    val tree = SidebarSessionTree(active)
    val unpinned = tree.roots.filterNot { it.isPinned }
    val expandedProjectIds = projects
        .filter { sidebar.isProjectsExpanded && it.id !in sidebar.collapsedProjects }
        .map { it.id }
        .toSet()
    return SidebarContent(
        pinned = tree.rows(tree.roots.filter { it.isPinned }).toImmutableList(),
        projects = projects.map { project ->
            ProjectGroupUi(
                project = project,
                sessions = tree.rows(unpinned.filter { it.projectId == project.id }).toImmutableList(),
                isExpanded = project.id !in sidebar.collapsedProjects,
            )
        }.toImmutableList(),
        recent = tree.rows(unpinned.filter { it.projectId !in expandedProjectIds }).toImmutableList(),
        archived = archived,
    )
}
