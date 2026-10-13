package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionActivity
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeNode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioCompactionPort
import io.aequicor.heartbeat.feature.aistudio.screenmodel.compactedChat
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StudioCompactionModelTest {
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0, sessionId = "chat")),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
        defaultProjectId = "p-heartbeat",
    )

    @Test
    fun `a compact command compacts the chat instead of sending a prompt`() = runTest {
        val compactions = FakeCompactions()
        val fixture = AiStudioModelTest.Fixture(this, ready, compactions = compactions)
        val screen = fixture.subscribe()

        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "/compact keep tests"))
        fixture.model.store.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()

        assertEquals<List<Pair<String, String?>>>(listOf("chat" to "keep tests"), compactions.requests)
        assertTrue(fixture.machine.sent.none { it is AiStudioIntent.Public.Submit })
        assertEquals("", screen.states.value.draft(0))
    }

    @Test
    fun `a compact command that cannot be queued keeps the draft and marks the pane`() = runTest {
        val compactions = FakeCompactions(isQueued = false)
        val fixture = AiStudioModelTest.Fixture(this, ready, compactions = compactions)
        val screen = fixture.subscribe()

        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "/compact"))
        fixture.model.store.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()

        assertTrue(fixture.machine.sent.none { it is AiStudioIntent.Public.Submit })
        assertEquals("/compact", screen.states.value.draft(0))
        assertTrue(0 in screen.states.value.failedPanes)
    }

    @Test
    fun `the context indicator compacts the pane chat`() = runTest {
        val compactions = FakeCompactions()
        val fixture = AiStudioModelTest.Fixture(this, ready, compactions = compactions)
        fixture.subscribe()

        fixture.model.store.intent(AiStudioScreenIntent.CompactContext(0))
        runCurrent()

        assertEquals<List<Pair<String, String?>>>(listOf("chat" to null), compactions.requests)
    }

    @Test
    fun `without context optimization a compact command is sent as a prompt`() = runTest {
        val fixture = AiStudioModelTest.Fixture(this, ready)
        fixture.subscribe()

        fixture.model.store.intent(AiStudioScreenIntent.Suggestions.DraftChanged(0, "/compact"))
        fixture.model.store.intent(AiStudioScreenIntent.Submit(0))
        runCurrent()

        assertEquals("/compact", fixture.machine.sent.filterIsInstance<AiStudioIntent.Public.Submit>().single().prompt)
    }

    @Test
    fun `a compact command typed while a native sub-agent is shown compacts no chat`() {
        val root = SessionRef(EngineId("native"), SessionSourceId("profile"), "root")
        val child = root.copy(nativeId = "child")
        val tree = SessionTreeSnapshot(
            root,
            listOf(
                SessionTreeNode(root, null, "Root", SessionActivity.Running),
                SessionTreeNode(child, root, "Child", SessionActivity.Running),
            ),
            SessionTreeCoverage.Complete,
        )
        val state = AiStudioScreenState(
            panes = persistentListOf(PaneUi(0, sessionId = "chat")),
            nativeTrees = persistentMapOf("chat" to tree.toUi()),
            subSessions = persistentMapOf("chat" to nativeKey(child)),
        )

        assertNull(state.compactedChat(0))
        assertEquals("chat", state.copy(subSessions = persistentMapOf()).compactedChat(0))
    }

    private class FakeCompactions(private val isQueued: Boolean = true) : StudioCompactionPort {
        val requests = mutableListOf<Pair<String, String?>>()
        override val availability: Flow<Boolean> = flowOf(true)

        override suspend fun request(chatId: String, instructions: String?): Boolean {
            requests += chatId to instructions
            return isQueued
        }
    }
}
