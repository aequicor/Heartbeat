package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KoogToolPromptTest {
    @Test
    fun `tool continuation and restored history preserve call identity arguments and output`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.searchResults = listOf(SearchResult("https://example.com", "Example", "Snippet"))
        val session = fixture.session()
        session.features.require(SendsPrompts).send(fixture.request())
        fixture.executor.frames.trySend(
            StreamFrame.ToolCallComplete("call-1", "web_search", """{"query":"topic"}""", 0),
        )
        fixture.executor.frames.trySend(StreamFrame.End("tool_calls"))
        fixture.executor.complete("Answer")
        runCurrent()
        assertSearchExchange(fixture.executor.prompts.last())
        session.close()

        val resumed = fixture.runtime().attach(session.ref, ResumeSessionRequest(fixture.target))
        resumed.features.require(SendsPrompts).send(fixture.request("next"))
        fixture.executor.complete("Followup")
        runCurrent()
        assertSearchExchange(fixture.executor.prompts.last())
    }

    private fun assertSearchExchange(prompt: Prompt) {
        val parts = prompt.messages.flatMap { it.parts }
        val call = parts.filterIsInstance<MessagePart.Tool.Call>().single()
        assertEquals("call-1", call.id)
        assertEquals("web_search", call.tool)
        assertEquals("""{"query":"topic"}""", call.args)
        val result = parts.filterIsInstance<MessagePart.Tool.Result>().single()
        assertEquals(call.id, result.id)
        assertEquals(call.tool, result.tool)
        assertTrue(result.output.contains("https://example.com"))
        assertTrue(result.output.contains("Snippet"))
        assertFalse(result.isError)
    }
}
