@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** An empty draft is forgotten; editing clears a previous send failure. */
fun AiStudioScreenState.withDraft(paneId: Int, text: String): AiStudioScreenState = copy(
    diagramDraftReasons = (diagramDraftReasons - draftKey(paneId)).toImmutableMap(),
    drafts = draftKey(paneId).let { key -> if (text.isEmpty()) drafts - key else drafts + (key to text) }
        .toImmutableMap(),
    failedPanes = (failedPanes - paneId).toImmutableSet(),
)

/** The caret follows the end of a programmatic draft replacement until the editor reports a position. */
fun AiStudioScreenState.withDraftCaret(paneId: Int, caret: Int): AiStudioScreenState = copy(
    draftCarets = (draftCarets + (draftKey(paneId) to caret)).toImmutableMap(),
)

/** Local follow-up of an accepted navigation: the drawer closes, closed panes forget their drafts. */
fun AiStudioScreenState.afterNavigation(
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

/** Starts renaming [sessionId] in the sidebar; [origin] tells where the rename was started. */
fun AiStudioScreenState.startRename(sessionId: String, origin: String): AiStudioScreenState {
    val title = session(sessionId)?.title ?: return this
    return copy(sidebar = sidebar.copy(renaming = RenameUi(sessionId, title, origin)))
}

/** The sidebar after one of its own intents (mode, search, rename). */
fun SidebarUi.reduce(intent: AiStudioScreenIntent.Sidebar): SidebarUi = when (intent) {
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

    is AiStudioScreenIntent.ToggleProjectSessions -> copy(
        expandedProjectSessions = if (intent.projectId in expandedProjectSessions) {
            expandedProjectSessions - intent.projectId
        } else {
            expandedProjectSessions + intent.projectId
        }.toImmutableSet(),
    )

    AiStudioScreenIntent.ToggleProjectsSection -> copy(isProjectsExpanded = !isProjectsExpanded)

    AiStudioScreenIntent.ToggleRecentSection -> copy(isRecentExpanded = !isRecentExpanded)

    AiStudioScreenIntent.ToggleNoProjectSection -> copy(isNoProjectExpanded = !isNoProjectExpanded)

    AiStudioScreenIntent.ToggleSidebar -> copy(isVisible = !isVisible)

    is AiStudioScreenIntent.SetDrawerOpen -> copy(isDrawerOpen = intent.isOpen)
}

/** Unpinned sessions of one project; the five newest roots keep their complete helper subtrees visible. */
@Serializable
@SerialName("ProjectGroupUi")
@Immutable
data class ProjectGroupUi(
    val project: ProjectUi,
    val sessions: ImmutableList<SessionUi>,
    val isExpanded: Boolean,
    val isShowingAllSessions: Boolean = false,
) {
    /** The full list remains in [sessions], so hiding older roots never moves them to another section. */
    val visibleSessions: ImmutableList<SessionUi>
        get() {
            if (isShowingAllSessions) return sessions
            var roots = 0
            return sessions.takeWhile { session ->
                if (session.depth == 0) roots += 1
                roots <= PROJECT_SESSION_LIMIT
            }.toImmutableList()
        }

    /** Whether this project needs a show-more or show-less control. */
    val hasMoreSessions: Boolean get() = hiddenSessionCount > 0

    /** Root chats beyond the compact list; helper descendants do not count against its limit. */
    val hiddenSessionCount: Int get() = (sessions.count { it.depth == 0 } - PROJECT_SESSION_LIMIT).coerceAtLeast(0)
}

private const val PROJECT_SESSION_LIMIT = 5

/**
 * Sidebar lists, newest first within each group. Root chats select their section: pinned, expanded project,
 * [noProject], then [recent]. Helper descendants follow their root without appearing in another section.
 * Collapsing a project or the projects section returns its unpinned sessions to [recent].
 * The renderer controls visibility of recent and projectless sections without changing their contents.
 * With a query: [results] only. Archived sessions are listed separately.
 */
@Serializable
@SerialName("SidebarContent")
@Immutable
data class SidebarContent(
    val pinned: ImmutableList<SessionUi> = persistentListOf(),
    val projects: ImmutableList<ProjectGroupUi> = persistentListOf(),
    val recent: ImmutableList<SessionUi> = persistentListOf(),
    val results: ImmutableList<SessionUi>? = null,
    val archived: ImmutableList<SessionUi> = persistentListOf(),
    val noProject: ImmutableList<SessionUi> = persistentListOf(),
)

/** Groups the sessions of [state] for the sidebar. */
fun sidebarContent(state: AiStudioScreenState): SidebarContent = sidebarContent(
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
                isShowingAllSessions = project.id in sidebar.expandedProjectSessions,
            )
        }.toImmutableList(),
        recent = tree.rows(unpinned.filter { it.projectId != null && it.projectId !in expandedProjectIds })
            .toImmutableList(),
        archived = archived,
        noProject = tree.rows(unpinned.filter { it.projectId == null }).toImmutableList(),
    )
}
