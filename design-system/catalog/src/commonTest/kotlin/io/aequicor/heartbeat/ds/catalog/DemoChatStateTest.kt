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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DemoChatStateTest {
    @Test
    fun `blank sends leave the transcript unchanged`() = runTest {
        val state = DemoChatState(this, copy)
        val original = state.messages
        state.updateDraft("   ")
        state.send(copy)
        assertEquals(original, state.messages)
        assertFalse(state.isStreaming)
    }

    @Test
    fun `send streams into one stable message and completes`() = runTest {
        val state = DemoChatState(this, copy)
        val initialCount = state.messages.size
        state.updateDraft("an idea")
        state.send(copy)
        val streamedId = state.messages.last().id
        assertTrue(state.isStreaming)
        assertEquals("", state.draft)
        assertEquals(initialCount + 2, state.messages.size)
        advanceUntilIdle()
        assertFalse(state.isStreaming)
        assertEquals(streamedId, state.messages.last().id)
        assertEquals(copy.response, state.messages.last().text)
        assertEquals(HbMessageStatus.Complete, state.messages.last().status)
    }

    @Test
    fun `stop retains partial text and prevents later chunks`() = runTest {
        val state = DemoChatState(this, copy)
        state.updateDraft("an idea")
        state.send(copy)
        runCurrent()
        advanceTimeBy(140)
        runCurrent()
        assertTrue(state.messages.last().text.isNotEmpty())
        assertTrue(state.messages.last().text.length < copy.response.length)
        state.stop()
        val partial = state.messages.last().text
        advanceUntilIdle()
        assertFalse(state.isStreaming)
        assertEquals(partial, state.messages.last().text)
        assertEquals(HbMessageStatus.Complete, state.messages.last().status)
    }

    @Test
    fun `reset cancels the active generation and ignores its remaining chunks`() = runTest {
        val state = DemoChatState(this, copy)
        val initial = state.messages
        state.updateDraft("an idea")
        state.send(copy)
        runCurrent()
        state.reset(copy)
        advanceUntilIdle()
        assertEquals(initial, state.messages)
        assertFalse(state.isStreaming)
        assertEquals("", state.draft)
    }

    @Test
    fun `repeated sends during streaming are ignored and completed sends keep unique ids`() = runTest {
        val state = DemoChatState(this, copy)
        state.updateDraft("first")
        state.send(copy)
        val size = state.messages.size
        state.updateDraft("second")
        state.send(copy)
        assertEquals(size, state.messages.size)
        advanceUntilIdle()
        state.send(copy)
        advanceUntilIdle()
        assertEquals(size + 2, state.messages.size)
        assertEquals(state.messages.size, state.messages.map { it.id }.toSet().size)
    }

    private companion object {
        val copy = ChatDemoCopy(
            user = "User",
            agent = "Agent",
            tool = "Tool",
            studio = "Studio",
            prompt = "Prompt",
            reply = "Reply",
            toolResult = "Ready",
            notice = "Local demo",
            code = "sample()",
            codeLabel = "Code",
            response = "A response long enough to be delivered in several cancellable stream chunks.",
        )
    }
}
