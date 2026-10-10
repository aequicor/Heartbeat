package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.impl.ui.sidebarInput
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SidebarHelpersTest {
    private val project = ProjectUi("project", "Project", EnvironmentUi.Local, "main")

    @Test
    fun `helpers follow their root section and keep sibling recency without duplicating pinned children`() {
        val sessions = listOf(
            row("child", 5, "root").copy(isPinned = true),
            row("root", 1),
            row("sibling", 4, "root"),
            row("grandchild", 6, "child"),
            row("parentless", 3),
        )
        val content = sidebarContent(listOf(project), sessions, setOf("child"), SidebarUi())
        assertTrue(content.pinned.isEmpty())
        assertEquals(listOf("parentless", "root", "child", "grandchild", "sibling"), content.recent.map { it.id })
        assertEquals(listOf(0, 0, 1, 2, 1), content.recent.map { it.depth })
        assertTrue(content.recent.single { it.id == "child" }.isRunning)
        val pinned = sidebarContent(
            listOf(project),
            sessions.map { if (it.id == "root") it.copy(isPinned = true) else it },
            emptySet(),
            SidebarUi(),
        )
        assertEquals(listOf("root", "child", "grandchild", "sibling"), pinned.pinned.map { it.id })
        assertEquals(listOf("parentless"), pinned.recent.map { it.id })
    }

    @Test
    fun `collapsed project moves the complete helper subtree to recent`() {
        val sessions = listOf(row("child", 3, "root"), row("root", 1).copy(projectId = project.id))
        val expanded = sidebarContent(listOf(project), sessions, emptySet(), SidebarUi())
        assertEquals(listOf("root", "child"), expanded.projects.single().sessions.map { it.id })
        assertTrue(expanded.recent.isEmpty())
        val collapsed = sidebarContent(
            listOf(project),
            sessions,
            emptySet(),
            SidebarUi(collapsedProjects = persistentSetOf(project.id)),
        )
        assertEquals(listOf("root", "child"), collapsed.recent.map { it.id })
        assertEquals(listOf(0, 1), collapsed.recent.map { it.depth })
    }

    @Test
    fun `missing archived and cyclic parents never hide helpers or loop`() {
        val sessions = listOf(
            row("orphan", 8, "missing"),
            row("self", 7, "self"),
            row("a", 6, "b"),
            row("b", 5, "a"),
            row("cycle-child", 4, "a"),
            row("archived", 3).copy(isArchived = true),
            row("live-child", 2, "archived"),
        )
        val content = sidebarContent(emptyList(), sessions, emptySet(), SidebarUi())
        assertEquals(sessions.filterNot { it.isArchived }.map { it.id }, content.recent.map { it.id })
        assertTrue(content.recent.all { it.depth == 0 && !it.isNestedInSidebar })
        assertEquals(listOf("archived"), content.archived.map { it.id })
    }

    @Test
    fun `search displays matching child as root when caller is absent and preserves matched nesting`() {
        val sessions = listOf(row("match-child", 3, "root"), row("root", 1))
        val childOnly = sidebarContent(emptyList(), sessions, emptySet(), SidebarUi(query = "match"))
        assertEquals(listOf("match-child"), childOnly.results?.map { it.id })
        assertEquals(listOf(0), childOnly.results?.map { it.depth })
        assertTrue(childOnly.results!!.single().isNestedInSidebar)
        val both = sidebarContent(
            emptyList(),
            sessions.map { it.copy(title = "match ${it.title}") },
            emptySet(),
            SidebarUi(query = "match"),
        )
        assertEquals(listOf("root", "match-child"), both.results?.map { it.id })
        assertEquals(listOf(0, 1), both.results?.map { it.depth })
    }

    @Test
    fun `workspace parent identity and live permission state reach sidebar independently of running`() {
        val workspace = StudioWorkspace(
            emptyList(),
            listOf(StudioSession("helper", null, "Helper", Instant.DISTANT_PAST, parentChatId = "parent")),
        )
        val screen = AiStudioScreenState(
            running = persistentSetOf("helper"),
            permissions = persistentListOf(PermissionUi("helper", "request", "Approve", persistentListOf())),
        ).withWorkspace(workspace)
        assertEquals("parent", screen.sessions.single().parentChatId)
        assertEquals(setOf("helper"), screen.sidebarInput().awaitingPermission)
        val waiting = sidebarContent(screen).recent.single()
        assertTrue(waiting.isAwaitingPermission)
        assertTrue(waiting.isWaiting)
        assertTrue(waiting.isRunning)
        assertFalse(sidebarContent(screen.copy(permissions = persistentListOf())).recent.single().isAwaitingPermission)
    }

    private fun row(id: String, order: Long, parent: String? = null) = SessionUi(
        id,
        id,
        null,
        Instant.fromEpochSeconds(order),
        parentChatId = parent,
    )
}
