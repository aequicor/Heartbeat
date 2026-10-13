package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessChoicesUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessOptionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.pendingSubmission
import io.aequicor.heartbeat.feature.aistudio.screenui.harnessChoices
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StudioHarnessProjectTest {
    private val shared = HarnessOptionUi("shared", "Shared", "Available everywhere")
    private val projectA = HarnessOptionUi("a", "Project A", projectIds = persistentSetOf("project-a"))
    private val projectB = HarnessOptionUi("b", "Project B", projectIds = persistentSetOf("project-b"))
    private val state = AiStudioScreenState(
        panes = persistentListOf(PaneUi(0, projectId = "project-a")),
        harnessChoices = HarnessChoicesUi(options = persistentListOf(shared, projectA, projectB)),
    )

    @Test
    fun `shared harnesses are offered everywhere while project harnesses stay in their project`() {
        assertEquals(listOf("shared", "a"), state.harnessChoices(PaneUi(0, projectId = "project-a")).map { it.id })
        assertEquals(listOf("shared", "b"), state.harnessChoices(PaneUi(0, projectId = "project-b")).map { it.id })
        assertEquals(listOf("shared"), state.harnessChoices(PaneUi(0)).map { it.id })
        assertEquals("Available everywhere", state.harnessChoices(PaneUi(0)).single().description)
        assertFalse(state.harnessChoices(PaneUi(0)).single().isSelected)
    }

    @Test
    fun `a project without available harnesses has no choices`() {
        val restricted = state.copy(harnessChoices = HarnessChoicesUi(options = persistentListOf(projectA)))
        assertEquals(emptyList(), restricted.harnessChoices(PaneUi(0, projectId = "project-b")))
        assertEquals(emptyList(), restricted.harnessChoices(PaneUi(0)))
    }

    @Test
    fun `switching project drops invisible selections from the pane and first prompt`() {
        val selected = state.copy(
            harnessChoices = state.harnessChoices.copy(panes = persistentMapOf(0 to persistentSetOf("a", "shared"))),
        )
        val switched = selected.reflectMachine(
            AiStudioState.Ready(
                panes = listOf(StudioPane(0, projectId = "project-b")),
                focusedPaneId = 0,
                settings = DefaultRunSettings,
                defaultProjectId = "project-b",
            ),
        )
        assertEquals<Set<String>?>(setOf("shared"), switched.harnessChoices.panes[0])
        assertEquals(setOf("shared"), switched.pendingSubmission(0, "request").harnesses)
        // Even a stale selection restored after failed creation is filtered at submission time.
        val stale = switched.copy(harnessChoices = selected.harnessChoices)
        assertEquals(setOf("shared"), stale.pendingSubmission(0, "retry").harnesses)
    }

    @Test
    fun `connected project harness remains reachable for detachment in an unrelated chat`() {
        val connected = state.copy(
            harnessChoices = state.harnessChoices.copy(chats = persistentMapOf("chat" to persistentSetOf("a"))),
        )
        val choices = connected.harnessChoices(PaneUi(0, sessionId = "chat", projectId = "project-b"))
        assertEquals(listOf("shared", "a", "b"), choices.map { it.id })
        assertEquals(listOf("a"), choices.filter { it.isSelected }.map { it.id })
    }
}
