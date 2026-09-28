package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class AiStudioPresentationTest {
    private val project = ProjectUi("p", "heartbeat", EnvironmentUi.Local, "master")
    private val state = AiStudioScreenState(
        projects = persistentListOf(project),
        sessions = persistentListOf(
            session("old", projectId = "p", minute = 1),
            session("pinned", projectId = "p", minute = 2, isPinned = true),
            session("new", projectId = "p", minute = 3),
            session("archived", projectId = "p", minute = 4, isArchived = true),
            session("outside", projectId = null, minute = 0),
        ),
        running = persistentSetOf("new"),
    )

    @Test
    fun `sidebar shows each active session once with pinned then project then recent priority`() {
        val content = sidebarContent(state)
        assertEquals(listOf("pinned"), content.pinned.map { it.id })
        assertEquals(listOf("new", "old"), content.projects.single().sessions.map { it.id })
        assertEquals(listOf("outside"), content.recent.map { it.id })
        assertEquals(listOf("archived"), content.archived.map { it.id })
        assertTrue(content.projects.single().sessions.first().isRunning)
        val displayed = content.pinned + content.projects.flatMap { it.sessions } + content.recent
        assertEquals(listOf("new", "old", "outside", "pinned"), displayed.map { it.id }.sorted())
    }

    @Test
    fun `collapsing a project returns its unpinned sessions to recent newest first`() {
        val collapsed = state.copy(sidebar = state.sidebar.reduce(AiStudioScreenIntent.ToggleProject("p")))
        val content = sidebarContent(collapsed)
        assertEquals(listOf("pinned"), content.pinned.map { it.id })
        assertEquals(false, content.projects.single().isExpanded)
        assertEquals(listOf("new", "old", "outside"), content.recent.map { it.id })
        assertTrue(content.recent.first().isRunning)

        val expanded = collapsed.copy(sidebar = collapsed.sidebar.reduce(AiStudioScreenIntent.ToggleProject("p")))
        assertEquals(sidebarContent(state), sidebarContent(expanded))
    }

    @Test
    fun `collapsing projects keeps pinned visible and all other sessions available in recent`() {
        val collapsed = state.sidebar.reduce(AiStudioScreenIntent.ToggleProjectsSection)
        val content = sidebarContent(state.copy(sidebar = collapsed))
        assertEquals(listOf("pinned"), content.pinned.map { it.id })
        assertEquals(listOf("new", "old", "outside"), content.recent.map { it.id })

        val recentCollapsed = collapsed.reduce(AiStudioScreenIntent.ToggleRecentSection)
        assertEquals(content, sidebarContent(state.copy(sidebar = recentCollapsed)))
    }

    @Test
    fun `sessions whose project is unavailable remain in recent`() {
        val orphaned = state.copy(projects = persistentListOf())
        val content = sidebarContent(orphaned)
        assertEquals(listOf("pinned"), content.pinned.map { it.id })
        assertEquals(listOf("new", "old", "outside"), content.recent.map { it.id })
        assertTrue(content.projects.isEmpty())
    }

    @Test
    fun `search lists matching sessions of the current mode only`() {
        val searching = state.copy(sidebar = SidebarUi(query = " OL "))
        assertEquals(listOf("old"), sidebarContent(searching).results?.map { it.id })
        val archive = state.copy(sidebar = SidebarUi(mode = SidebarMode.Archive, query = "arch"))
        assertEquals(listOf("archived"), sidebarContent(archive).results?.map { it.id })
    }

    @Test
    fun `search includes pinned and collapsed project sessions once without changing the archive`() {
        val searching = state.copy(
            sidebar = SidebarUi(
                query = " N ",
                collapsedProjects = persistentSetOf("p"),
                isProjectsExpanded = false,
                isRecentExpanded = false,
            ),
        )
        val content = sidebarContent(searching)
        assertEquals(listOf("new", "pinned"), content.results?.map { it.id })
        assertTrue(content.results.orEmpty().first().isRunning)
        assertTrue(content.pinned.isEmpty())
        assertTrue(content.projects.isEmpty())
        assertTrue(content.recent.isEmpty())
        assertEquals(listOf("archived"), content.archived.map { it.id })
    }

    @Test
    fun `archived pinned sessions stay exclusive to archive and archive search`() {
        val archivedPinned = session("pinned-archived", "p", minute = 5, isPinned = true, isArchived = true)
        val workspace = state.copy(sessions = (state.sessions + archivedPinned).toImmutableList())
        val content = sidebarContent(workspace)
        assertEquals(listOf("pinned-archived", "archived"), content.archived.map { it.id })
        assertEquals(listOf("pinned"), content.pinned.map { it.id })

        val activeSearch = workspace.copy(sidebar = SidebarUi(query = "pinned"))
        assertEquals(listOf("pinned"), sidebarContent(activeSearch).results?.map { it.id })
        val archiveSearch = workspace.copy(sidebar = SidebarUi(mode = SidebarMode.Archive, query = "pinned"))
        assertEquals(listOf("pinned-archived"), sidebarContent(archiveSearch).results?.map { it.id })
    }

    @Test
    fun `collapsed projects and sidebar toggles are local presentation`() {
        val collapsed = SidebarUi().reduce(AiStudioScreenIntent.ToggleProject("p"))
        assertEquals(false, sidebarContent(state.copy(sidebar = collapsed)).projects.single().isExpanded)
        val searching = SidebarUi(query = "x").reduce(AiStudioScreenIntent.ToggleSearch)
        assertEquals("", searching.query)
        assertTrue(searching.isSearchVisible)
        val hidden = SidebarUi(isVisible = false).reduce(AiStudioScreenIntent.ShowSidebarMode(SidebarMode.Archive))
        assertTrue(hidden.isVisible)
    }

    @Test
    fun `drafts are forgotten when emptied and editing clears a failure`() {
        val failed = state.restoreDraft(1, "Prompt")
        assertEquals("Prompt", failed.draft(1))
        val edited = failed.withDraft(1, "")
        assertEquals("", edited.draft(1))
        assertTrue(1 !in edited.failedPanes)
    }

    @Test
    fun `drafts follow the session shown in the pane`() {
        val first = AiStudioScreenState(panes = persistentListOf(PaneUi(0, sessionId = "a"))).withDraft(0, "for a")
        val switched = first.copy(panes = persistentListOf(PaneUi(0, sessionId = "b")))
        assertEquals("", switched.draft(0))
        assertEquals("for a", switched.copy(panes = first.panes).draft(0))
    }

    private fun session(
        id: String,
        projectId: String?,
        minute: Int,
        isPinned: Boolean = false,
        isArchived: Boolean = false,
    ) = SessionUi(
        id = id,
        title = id,
        projectId = projectId,
        updatedAt = Instant.fromEpochSeconds(minute * 60L),
        isPinned = isPinned,
        isArchived = isArchived,
    )
}
