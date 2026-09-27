package io.aequicor.heartbeat.ds.catalog

import io.aequicor.heartbeat.ds.components.HbMessageStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LongSessionStateTest {
    @Test
    fun `long session prepares ten thousand historical messages and one current reply`() = runTest {
        val state = DemoChatState(this, copy)
        state.loadLongSession(copy)
        assertTrue(state.isLoadingHistory)
        advanceUntilIdle()

        assertFalse(state.isLoadingHistory)
        assertEquals(10001, state.timeline.messageCount)
        assertEquals("long-latest", state.timeline.latestMessage!!.id)
        assertEquals(HbMessageStatus.Complete, state.timeline.latestMessage!!.status)
        assertTrue(state.messages.all { it.status == HbMessageStatus.Complete })
    }

    @Test
    fun `streaming a long session shares history and prepending cannot replace the active tail`() = runTest {
        val state = DemoChatState(this, copy)
        state.loadLongSession(copy)
        advanceUntilIdle()
        val firstMessage = state.messages.first()
        state.updateDraft("Continue this session")
        state.send(copy)
        val activeId = state.timeline.latestMessage!!.id
        runCurrent()
        advanceTimeBy(140)
        runCurrent()
        assertTrue(state.timeline.latestMessage!!.text.isNotEmpty())
        assertSame(firstMessage, state.messages.first())

        state.loadEarlier(copy)
        assertTrue(state.isStreaming)
        assertEquals(activeId, state.timeline.latestMessage!!.id)
        assertSame(firstMessage, state.messages[100])
        advanceUntilIdle()

        assertEquals(10103, state.timeline.messageCount)
        assertEquals(activeId, state.timeline.latestMessage!!.id)
        assertEquals(copy.response, state.timeline.latestMessage!!.text)
        assertEquals(HbMessageStatus.Complete, state.timeline.latestMessage!!.status)
        assertSame(firstMessage, state.messages[100])
    }

    @Test
    fun `reset cancels queued long session preparation and keeps the fresh conversation`() = runTest {
        val state = DemoChatState(this, copy)
        val initial = state.messages
        state.loadLongSession(copy)
        state.reset(copy)
        advanceUntilIdle()
        assertFalse(state.isLoadingHistory)
        assertFalse(state.isStreaming)
        assertEquals(initial, state.messages)
    }

    private companion object {
        val copy = ChatDemoCopy(
            user = "User",
            agent = "Agent",
            tool = "Tool",
            studio = "Studio",
            prompt = "A concise historical prompt",
            reply = "## Reply\n\nA historical response.",
            toolResult = "Ready",
            notice = "Local demo",
            code = "sample()",
            codeLabel = "Code",
            response = "## Next step\n\nA response long enough to observe " +
                "several incremental updates before completion.",
            section = "Current session",
            historySection = "Earlier session",
        )
    }
}
