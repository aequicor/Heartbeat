package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioProject
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet

/** Every machine state maps to a phase: no `else`, so a new machine state breaks the build here. */
internal fun AiStudioScreenState.reflectMachine(machine: AiStudioState): AiStudioScreenState = when (machine) {
    AiStudioState.Idle, AiStudioState.Loading -> copy(phase = StudioPhase.Loading)

    AiStudioState.Disabled -> copy(phase = StudioPhase.Disabled)

    AiStudioState.LoadError -> copy(phase = StudioPhase.Error)

    is AiStudioState.Ready -> copy(
        phase = StudioPhase.Ready,
        contexts = machine.contexts.mapValues { it.value.toUi() }.toImmutableMap(),
        providerUsage = machine.providerUsage.mapValues { it.value.toUi() }.toImmutableMap(),
        isProjectAddingAvailable = machine.isProjectAddingAvailable,
        isWorktreeAvailable = machine.isWorktreeAvailable,
        addingProjectTo = machine.addingProjectTo,
        projectErrorPane = machine.projectErrorPane,
        permissions = machine.permissions.map { request ->
            PermissionUi(
                request.sessionId,
                request.requestId,
                request.title,
                request.options.map { PermissionOptionUi(it.id, it.title) }.toImmutableList(),
            )
        }.toImmutableList(),
        panes = machine.panes.map { it.toUi() }.toImmutableList(),
        focusedPaneId = machine.focusedPaneId,
        running = machine.running.toImmutableSet(),
        runStartedAt = machine.runStartedAt.toImmutableMap(),
        stopping = machine.stopping.toImmutableSet(),
        stopFailures = machine.stopFailures.toImmutableSet(),
        uncancellable = machine.uncancellable.toImmutableSet(),
        settings = machine.settings.toUi().copy(engineEfforts = settings.engineEfforts),
        configurations = machine.configurations.mapValues { it.value.toUi() }.toImmutableMap(),
    )
}

internal fun AiStudioScreenState.withWorkspace(workspace: StudioWorkspace): AiStudioScreenState = copy(
    projects = workspace.projects.map { it.toUi() }.toImmutableList(),
    sessions = workspace.sessions.map { it.toUi() }.toImmutableList(),
)

/** An empty draft is forgotten; editing clears a previous send failure. */
internal fun AiStudioScreenState.withDraft(paneId: Int, text: String): AiStudioScreenState = copy(
    drafts = draftKey(paneId).let { key -> if (text.isEmpty()) drafts - key else drafts + (key to text) }
        .toImmutableMap(),
    failedPanes = (failedPanes - paneId).toImmutableSet(),
)

/** Outputs can lag behind navigation and state reflection; restore only into the owning new-session draft. */
internal fun AiStudioScreenState.restoreDraft(
    output: AiStudioOutput.SubmitFailed,
    machine: AiStudioState,
): AiStudioScreenState {
    val pane = (machine as? AiStudioState.Ready)?.panes?.firstOrNull { it.id == output.paneId } ?: return this
    if (pane.sessionId != null || pane.isCreating || pane.createRequestId != output.requestId) return this
    val key = "pane:${output.paneId}"
    if (!drafts[key].isNullOrEmpty()) return this
    return copy(
        drafts = (drafts + (key to output.prompt)).toImmutableMap(),
        failedPanes = (failedPanes + output.paneId).toImmutableSet(),
    )
}

/** Local follow-up of an accepted navigation: the drawer closes, closed panes forget their drafts. */
internal fun AiStudioScreenState.afterNavigation(intent: AiStudioScreenIntent.Navigation): AiStudioScreenState =
    when (intent) {
        is AiStudioScreenIntent.NewSession, is AiStudioScreenIntent.OpenSession, is AiStudioScreenIntent.OpenBeside ->
            copy(sidebar = sidebar.copy(isDrawerOpen = false))

        is AiStudioScreenIntent.ClosePane -> copy(
            drafts = (drafts - "pane:${intent.paneId}").toImmutableMap(),
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
 * pinned sessions take priority over expanded project groups, then remaining sessions go to [recent].
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
internal fun sidebarContent(state: AiStudioScreenState): SidebarContent =
    sidebarContent(state.projects, state.sessions, state.running, state.sidebar)

/** Groups [sessions] using running flags from [running] and visible list precedence from [sidebar]. */
fun sidebarContent(
    projects: List<ProjectUi>,
    sessions: List<SessionUi>,
    running: Set<String>,
    sidebar: SidebarUi,
): SidebarContent {
    val ordered = sessions
        .map { it.copy(isRunning = it.id in running) }
        .sortedByDescending { it.updatedAt }
    val active = ordered.filterNot { it.isArchived }
    val archived = ordered.filter { it.isArchived }.toImmutableList()
    val query = sidebar.query.trim()
    if (query.isNotEmpty()) {
        val source = if (sidebar.mode == SidebarMode.Archive) archived else active
        return SidebarContent(
            results = source.filter { it.title.contains(query, ignoreCase = true) }.toImmutableList(),
            archived = archived,
        )
    }
    val unpinned = active.filterNot { it.isPinned }
    val expandedProjectIds = projects
        .filter { sidebar.isProjectsExpanded && it.id !in sidebar.collapsedProjects }
        .map { it.id }
        .toSet()
    return SidebarContent(
        pinned = active.filter { it.isPinned }.toImmutableList(),
        projects = projects.map { project ->
            ProjectGroupUi(
                project = project,
                sessions = unpinned.filter { it.projectId == project.id }.toImmutableList(),
                isExpanded = project.id !in sidebar.collapsedProjects,
            )
        }.toImmutableList(),
        recent = unpinned.filter { it.projectId !in expandedProjectIds }.toImmutableList(),
        archived = archived,
    )
}

internal fun StudioProject.toUi(): ProjectUi = ProjectUi(id, name, environment.toUi(), branch)

internal fun StudioSession.toUi(): SessionUi = SessionUi(
    id, title, projectId, updatedAt, isPinned, isUnread, isArchived, branch,
    modelId = modelId,
    isContinuable = isContinuable,
    isWorktree = isWorktree,
)
