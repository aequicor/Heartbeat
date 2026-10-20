package io.aequicor.heartbeat.feature.aistudio.screenui

import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EngineSwitchFailureUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EngineSwitchUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionTreeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionKindUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionStateUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioEngineSwitchingTest {
    private val pane = PaneUi(0, sessionId = "chat")
    private val session = SessionUi("chat", "Work", "project", Instant.DISTANT_PAST, modelId = "a")
    private val a = ModelUi("a", "A", isLocalProjectSupported = true, connectionKey = "one", engineRoute = "one")
    private val same = a.copy(id = "same", name = "Same")
    private val b = a.copy(id = "b", name = "B", connectionKey = "two", engineRoute = "two")
    private val cloud = b.copy(id = "cloud", isLocalProjectSupported = false)
    private val state = AiStudioScreenState(
        isEngineSwitchingAvailable = true,
        sessions = persistentListOf(session),
        panes = persistentListOf(pane, pane.copy(id = 1)),
        models = persistentListOf(a, same, b, cloud),
    )

    @Test
    fun `disabled flag and excluded chats preserve native model filtering`() {
        val disabled = state.copy(isEngineSwitchingAvailable = false)
        assertEquals(listOf(a, same), disabled.paneContent(pane).models)
        val organic = state.copy(sessions = persistentListOf(session.copy(isOrganism = true)))
        assertEquals(listOf(a, same), organic.paneContent(pane).models)
        val child = SubSessionUi("child", SubSessionKindUi.Agent, "Child", SubSessionStateUi.Completed)
        val selected = state.copy(
            nativeTrees = persistentMapOf("chat" to SessionTreeUi(persistentListOf(child), 0, true)),
            subSessions = persistentMapOf("chat" to "child"),
        )
        assertEquals(listOf(a, same), selected.paneContent(pane).models)
    }

    @Test
    fun `idle menu offers other bindings with workspace and helper refusals`() {
        val models = state.paneContent(pane).models
        assertEquals(listOf("a", "same", "b", "cloud"), models.map { it.id })
        assertNull(models[2].switchFailure)
        assertEquals(EngineSwitchFailureUi.Workspace, models[3].switchFailure)
        val helper = state.copy(sessions = persistentListOf(session.copy(isHelper = true)))
        assertEquals(EngineSwitchFailureUi.Unsupported, helper.paneContent(pane).models[2].switchFailure)
        val worktree = state.copy(
            sessions = persistentListOf(session.copy(projectId = null, executionWorkspace = "wt")),
        )
        assertEquals(EngineSwitchFailureUi.Workspace, worktree.paneContent(pane).models[3].switchFailure)
    }

    @Test
    fun `pending operation reaches both panes and blocks repeated settings`() {
        val switching = state.copy(engineSwitches = persistentMapOf("chat" to EngineSwitchUi(isPending = true)))
        for (item in switching.panes) {
            val content = switching.paneContent(item)
            assertTrue(content.isSettingPending)
            assertFalse(content.isCompactReady)
            assertEquals(EngineSwitchFailureUi.Busy, content.models[2].switchFailure)
            assertNull(content.models[1].switchFailure)
        }
        val stopping = state.copy(stopping = persistentSetOf("chat"))
        assertEquals(EngineSwitchFailureUi.Busy, stopping.paneContent(pane).models[2].switchFailure)
        val running = state.copy(running = persistentSetOf("chat"))
        assertEquals(EngineSwitchFailureUi.Busy, running.paneContent(pane).models[2].switchFailure)
    }
}
