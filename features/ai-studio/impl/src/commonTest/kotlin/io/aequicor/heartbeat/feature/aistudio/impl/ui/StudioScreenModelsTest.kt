package io.aequicor.heartbeat.feature.aistudio.impl.ui

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.RenameUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StudioScreenModelsTest {
    private val heartbeat = ProjectUi("p", "heartbeat", EnvironmentUi.Local, "master")
    private val site = ProjectUi("q", "site", EnvironmentUi.Cloud, "main")
    private val session = SessionUi("s", "Build", projectId = "q", updatedAt = Instant.fromEpochSeconds(0))
    private val state = AiStudioScreenState(
        panes = persistentListOf(PaneUi(0, sessionId = "s"), PaneUi(1, projectId = null)),
        focusedPaneId = 0,
        projects = persistentListOf(heartbeat, site),
        sessions = persistentListOf(session),
        transcripts = persistentMapOf(
            "s" to persistentListOf(
                MessageUi.Prompt("m1", Instant.fromEpochSeconds(10), "first"),
                MessageUi.Prompt("m2", Instant.fromEpochSeconds(100), "second"),
            ),
        ),
        now = Instant.fromEpochSeconds(130),
    )

    @Test
    fun `research entry requires flag Koog and a new projectless pane`() {
        val koog = state.copy(
            isResearchEnabled = true,
            models = persistentListOf(ModelUi(state.settings.modelId, "Koog", isResearchSupported = true)),
        )
        val newChat = PaneUi(7)
        assertTrue(koog.paneContent(newChat).isResearchAvailable)
        assertEquals(false, koog.copy(isResearchEnabled = false).paneContent(newChat).isResearchAvailable)
        assertEquals(false, koog.paneContent(newChat.copy(projectId = "p")).isResearchAvailable)
        assertEquals(false, koog.paneContent(newChat.copy(sessionId = "s")).isResearchAvailable)
        assertEquals(
            false,
            koog.copy(models = persistentListOf(ModelUi(state.settings.modelId, "Other")))
                .paneContent(newChat).isResearchAvailable,
        )
        assertEquals(false, koog.copy(models = persistentListOf()).paneContent(newChat).isResearchAvailable)
    }

    @Test
    fun `every session menu item maps to its intent and unknown ids are ignored`() {
        val pinned = session.copy(isPinned = true, isUnread = true)
        assertEquals(AiStudioScreenIntent.StartRename("s", "recent:s"), sessionAction(pinned, "rename", "recent:s"))
        assertEquals(AiStudioScreenIntent.SetPinned("s", false), sessionAction(pinned, "pin", "recent:s"))
        assertEquals(AiStudioScreenIntent.SetUnread("s", false), sessionAction(pinned, "unread", "recent:s"))
        assertEquals(AiStudioScreenIntent.OpenBeside("s"), sessionAction(pinned, "beside", "recent:s"))
        assertEquals(AiStudioScreenIntent.SetArchived("s", true), sessionAction(pinned, "archive", "recent:s"))
        assertNull(sessionAction(pinned, "delete", "recent:s"))
    }

    @Test
    fun `elapsed time counts from the latest prompt only while the session runs`() {
        val pane = state.panes.first()
        assertNull(state.paneContent(pane).elapsed)

        val running = state.copy(running = persistentSetOf("s"))
        assertEquals(30.seconds, running.paneContent(pane).elapsed)
        assertEquals(0.seconds, running.copy(now = Instant.fromEpochSeconds(50)).paneContent(pane).elapsed)
    }

    @Test
    fun `pane content resolves the session project and the rename of its header only`() {
        val renaming = state.copy(sidebar = SidebarUi(renaming = RenameUi("s", "New", paneOrigin(0))))
        val content = renaming.paneContent(renaming.panes.first())
        assertEquals(site, content.project)
        assertTrue(content.isFocused)
        assertEquals("New", content.renaming?.title)
        assertNull(renaming.paneContent(renaming.panes[1]).renaming)
    }

    @Test
    fun `new sessions use the project of the focused pane, then of its session, then the first one`() {
        assertEquals("s", state.sidebarInput().selectedId)
        assertEquals("q", state.sidebarInput().newSessionProjectId)
        val page = state.copy(panes = persistentListOf(PaneUi(0, projectId = "p")))
        assertEquals("p", page.sidebarInput().newSessionProjectId)
        val general = state.copy(panes = persistentListOf(PaneUi(0)))
        assertEquals("p", general.sidebarInput().newSessionProjectId)
        assertNull(general.copy(projects = persistentListOf()).sidebarInput().newSessionProjectId)
    }
}
