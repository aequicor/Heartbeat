package io.aequicor.heartbeat.feature.aistudio.impl.ui

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionActivity
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeNode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.NativeTranscriptUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.OrganismStatusUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.OrganismUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PrimarySubSession
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SubSessionKindUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SubSessionStateUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SubSessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.mergeSessionTrees
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.nativeKey
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.selectedNative
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.toUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioNativeSelectionTest {
    private val root = SessionRef(EngineId("native"), SessionSourceId("profile"), "root")
    private val child = root.copy(nativeId = "child")
    private val nested = root.copy(nativeId = "nested")
    private val tree = SessionTreeSnapshot(
        root,
        listOf(
            SessionTreeNode(root, null, "Root", SessionActivity.Running),
            SessionTreeNode(child, root, "Child", SessionActivity.Running),
            SessionTreeNode(nested, child, "Nested", SessionActivity.AwaitingUser),
        ),
        SessionTreeCoverage.Complete,
    )

    @Test
    fun `switching shows exact child transcript and preserves root scope and execution`() {
        val pane = PaneUi(0, sessionId = "chat")
        val rootMessages = persistentListOf<MessageUi>(MessageUi.Prompt("root", Instant.DISTANT_PAST, "root prompt"))
        val childMessages = persistentListOf<MessageUi>(MessageUi.Prompt("child", Instant.DISTANT_PAST, "child prompt"))
        val state = AiStudioScreenState(
            sessions = persistentListOf(SessionUi("chat", "Root", projectId = null, updatedAt = Instant.DISTANT_PAST)),
            transcripts = persistentMapOf("chat" to rootMessages),
            nativeTrees = persistentMapOf("chat" to tree.toUi()),
            nativeTranscripts = persistentMapOf("chat" to NativeTranscriptUi(nativeKey(child), childMessages)),
            subSessions = persistentMapOf("chat" to nativeKey(child)),
        )
        val selected = state.paneContent(pane)
        assertEquals(childMessages, selected.transcript)
        assertEquals(2, selected.nativeTree?.activeCount)
        assertFalse(selected.session!!.isContinuable)
        val switching = state.copy(subSessions = persistentMapOf("chat" to nativeKey(nested))).paneContent(pane)
        assertTrue(switching.transcript!!.isEmpty())
        assertEquals(2, switching.nativeTree?.activeCount)
        val returned = state.copy(subSessions = persistentMapOf("chat" to PrimarySubSession)).paneContent(pane)
        assertEquals(rootMessages, returned.transcript)
        assertTrue(returned.session!!.isContinuable)
        assertEquals(state.sessions, state.copy(subSessions = persistentMapOf()).sessions)
    }

    @Test
    fun `missing child falls back to the root for both rendering and submission`() {
        val pane = PaneUi(0, sessionId = "chat")
        val messages = persistentListOf<MessageUi>(MessageUi.Prompt("root", Instant.DISTANT_PAST, "root prompt"))
        val selected = AiStudioScreenState(
            sessions = persistentListOf(SessionUi("chat", "Root", projectId = null, updatedAt = Instant.DISTANT_PAST)),
            transcripts = persistentMapOf("chat" to messages),
            nativeTrees = persistentMapOf("chat" to tree.toUi()),
            subSessions = persistentMapOf("chat" to nativeKey(child)),
        )
        assertEquals(nativeKey(child), selected.selectedNative("chat"))
        val refreshed = selected.copy(nativeTrees = persistentMapOf("chat" to SessionTreeSnapshot(root).toUi()))
        val content = refreshed.paneContent(pane)
        assertEquals(PrimarySubSession, refreshed.selectedNative("chat"))
        assertEquals(PrimarySubSession, content.subSession)
        assertEquals(messages, content.transcript)
        assertTrue(content.session!!.isContinuable)
    }

    @Test
    fun `organic cells include nested native descendants without double counting native roots`() {
        val organic = OrganismUi(
            OrganismStatusUi.Developing,
            persistentListOf(
                SubSessionUi(PrimarySubSession, SubSessionKindUi.Zygote, "Root", SubSessionStateUi.Working),
                SubSessionUi("cell", SubSessionKindUi.Cell, "Cell", SubSessionStateUi.Working, depth = 1),
            ),
        )
        val merged = mergeSessionTrees(organic, mapOf("cell" to tree))
        assertEquals(listOf(0, 1, 2, 3), merged.sessions.map { it.depth })
        assertEquals(3, merged.activeCount)
        assertTrue(merged.isActivityKnown)
        val unknown = mergeSessionTrees(organic, mapOf("cell" to tree.copy(coverage = SessionTreeCoverage.Unsupported)))
        assertFalse(unknown.isActivityKnown)
        val completed = tree.copy(nodes = tree.nodes.map { it.copy(activity = SessionActivity.Completed) })
        assertEquals(1, mergeSessionTrees(organic, mapOf("cell" to completed)).activeCount)
    }
}
