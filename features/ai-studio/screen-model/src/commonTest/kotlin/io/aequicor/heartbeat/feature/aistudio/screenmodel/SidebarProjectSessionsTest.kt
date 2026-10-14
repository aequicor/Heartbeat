package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SidebarProjectSessionsTest {
    private val project = ProjectUi("project", "Project", EnvironmentUi.Local, "main")

    @Test
    fun `project lists show up to five newest roots and offer expansion only for overflow`() {
        for (count in listOf(0, 5, 6)) {
            val content = content((1..count).map { session("chat-$it", it) })
            val group = content.projects.single()
            assertEquals((count downTo 1).map { "chat-$it" }.take(5), group.visibleSessions.map { it.id })
            assertEquals(count, group.sessions.size)
            assertEquals(count > 5, group.hasMoreSessions)
            assertEquals((count - 5).coerceAtLeast(0), group.hiddenSessionCount)
            assertTrue(content.recent.isEmpty())
            assertTrue(content.noProject.isEmpty())
        }
    }

    @Test
    fun `showing all sessions is independent per project and survives collapsing the project`() {
        val second = project.copy(id = "second")
        val state = AiStudioScreenState(
            projects = persistentListOf(project, second),
            sessions = (1..6).flatMap {
                listOf(session("first-$it", it), session("second-$it", it).copy(projectId = second.id))
            }.toImmutableList(),
        )
        val expanded = state.sidebar.reduce(AiStudioScreenIntent.ToggleProjectSessions(project.id))
        val groups = sidebarContent(state.copy(sidebar = expanded)).projects
        assertEquals(listOf(6, 5), groups.map { it.visibleSessions.size })
        assertTrue(groups.first().isShowingAllSessions)
        assertFalse(groups.last().isShowingAllSessions)

        val collapsed = expanded.reduce(AiStudioScreenIntent.ToggleProject(project.id))
        val reopened = collapsed.reduce(AiStudioScreenIntent.ToggleProject(project.id))
        assertEquals(expanded, reopened)
        val compact = reopened.reduce(AiStudioScreenIntent.ToggleProjectSessions(project.id))
        assertEquals(state.sidebar, compact)
    }

    @Test
    fun `the fifth visible root keeps all descendants and the sixth root stays hidden`() {
        val roots = (1..6).map { session("root-$it", it) }
        val helpers = listOf(
            session("child", 20).copy(parentChatId = "root-2", projectId = null, isPinned = true),
            session("grandchild", 21).copy(parentChatId = "child", projectId = null),
            session("hidden-child", 22).copy(parentChatId = "root-1"),
        )
        val compact = content(roots + helpers)
        val group = compact.projects.single()
        assertEquals(
            listOf("root-6", "root-5", "root-4", "root-3", "root-2", "child", "grandchild"),
            group.visibleSessions.map { it.id },
        )
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 2), group.visibleSessions.map { it.depth })
        assertEquals(1, group.hiddenSessionCount)
        assertTrue(compact.pinned.isEmpty())
        assertTrue(compact.noProject.isEmpty())
        val all = content(roots + helpers, SidebarUi(expandedProjectSessions = persistentSetOf(project.id)))
        assertEquals(group.sessions, all.projects.single().visibleSessions)
    }

    @Test
    fun `pinned and archived roots do not consume the project limit and search finds hidden roots`() {
        val sessions = (1..6).map { session("root-$it", it) } + listOf(
            session("pinned", 7).copy(isPinned = true),
            session("archived", 8).copy(isArchived = true),
        )
        val compact = content(sessions)
        assertEquals(listOf("pinned"), compact.pinned.map { it.id })
        assertEquals(listOf("archived"), compact.archived.map { it.id })
        assertEquals(1, compact.projects.single().hiddenSessionCount)
        val found = content(sessions, SidebarUi(query = "root-1", isProjectsExpanded = false))
        assertEquals(listOf("root-1"), found.results?.map { it.id })
    }

    @Test
    fun `projectless section is independent and helpers follow their root project`() {
        val sessions = listOf(
            session("project", 1),
            session("outside", 2).copy(projectId = null),
            session("helper", 3).copy(parentChatId = "outside"),
            session("unavailable", 4).copy(projectId = "missing"),
            session("pinned", 5).copy(projectId = null, isPinned = true),
        )
        val sidebar = SidebarUi(collapsedProjects = persistentSetOf(project.id))
        val grouped = content(sessions, sidebar)
        assertEquals(listOf("outside", "helper"), grouped.noProject.map { it.id })
        assertEquals(listOf("unavailable", "project"), grouped.recent.map { it.id })
        assertEquals(listOf("pinned"), grouped.pinned.map { it.id })
        val hidden = sidebar.reduce(AiStudioScreenIntent.ToggleNoProjectSection)
        assertFalse(hidden.isNoProjectExpanded)
        assertTrue(hidden.isProjectsExpanded)
        assertTrue(hidden.isRecentExpanded)
        assertEquals(grouped, content(sessions, hidden))
        assertEquals(sidebar, hidden.reduce(AiStudioScreenIntent.ToggleNoProjectSection))
    }

    private fun content(sessions: List<SessionUi>, sidebar: SidebarUi = SidebarUi()) = sidebarContent(
        projects = listOf(project),
        sessions = sessions,
        running = emptySet(),
        sidebar = sidebar,
    )

    private fun session(id: String, order: Int) = SessionUi(
        id = id,
        title = id,
        projectId = project.id,
        updatedAt = Instant.fromEpochSeconds(order.toLong()),
    )
}
