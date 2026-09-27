package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
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
            session("pinned", projectId = null, minute = 2, isPinned = true),
            session("new", projectId = "p", minute = 3),
            session("archived", projectId = "p", minute = 4, isArchived = true),
        ),
        running = persistentSetOf("new"),
    )

    @Test
    fun `sidebar groups pinned, project and recent sessions newest first`() {
        val content = sidebarContent(state)
        assertEquals(listOf("pinned"), content.pinned.map { it.id })
        assertEquals(listOf("new", "old"), content.projects.single().sessions.map { it.id })
        assertEquals(listOf("new", "pinned", "old"), content.recent.map { it.id })
        assertEquals(listOf("archived"), content.archived.map { it.id })
        assertTrue(content.recent.first().isRunning)
    }

    @Test
    fun `search lists matching sessions of the current mode only`() {
        val searching = state.copy(sidebar = SidebarUi(query = " OL "))
        assertEquals(listOf("old"), sidebarContent(searching).results?.map { it.id })
        val archive = state.copy(sidebar = SidebarUi(mode = SidebarMode.Archive, query = "arch"))
        assertEquals(listOf("archived"), sidebarContent(archive).results?.map { it.id })
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
