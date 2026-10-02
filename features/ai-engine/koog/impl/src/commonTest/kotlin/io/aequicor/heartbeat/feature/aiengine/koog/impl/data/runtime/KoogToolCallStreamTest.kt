package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.StreamFrameFlowBuilder
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KoogToolCallStreamTest {
    @Test
    fun `sdk empty argument placeholders do not corrupt parallel call continuations`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.fetchedResource = ResourceContent("https://example.com", "Example", "Body")
        val session = fixture.session()
        session.features.require(SendsPrompts).send(fixture.request())
        val builder = StreamFrameFlowBuilder(FlowCollector { fixture.executor.frames.send(it) })
        builder.emitToolCallDelta("search", "web_search", null, 0)
        builder.emitReasoningDelta(null, "Thinking", null, 0)
        builder.emitToolCallDelta("fetch", "web_fetch", null, 1)
        builder.emitReasoningDelta(null, "Thinking", null, 0)
        builder.emitToolCallDelta(null, null, "{\"query\":", 0)
        builder.emitToolCallDelta(null, null, "{\"url\":", 1)
        builder.emitReasoningDelta(null, "Thinking", null, 0)
        builder.emitToolCallDelta(null, null, "\"topic\"}", 0)
        builder.emitToolCallDelta(null, null, "\"https://example.com\"}", 1)
        builder.emitEnd("tool_calls")
        fixture.executor.complete("Answer")
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        val calls = session.features.require(SessionHistory).page().items.filterIsInstance<SessionItem.ToolCall>()
        assertEquals(listOf("{\"query\":\"topic\"}", "{\"url\":\"https://example.com\"}"), calls.map { it.arguments })
        assertEquals(2, fixture.executor.prompts.size)
    }

    @Test
    fun `literal empty arguments survive reasoning flushes`() = runTest {
        val fixture = KoogTestFixture(this)
        val session = fixture.session()
        session.features.require(SendsPrompts).send(fixture.request())
        val builder = StreamFrameFlowBuilder(FlowCollector { fixture.executor.frames.send(it) })
        builder.emitToolCallDelta("search", "web_search", "{}", 0)
        builder.emitReasoningDelta(null, "Thinking", null, 0)
        builder.emitEnd("tool_calls")
        fixture.executor.complete("Answer")
        runCurrent()
        val calls = session.features.require(SessionHistory).page().items.filterIsInstance<SessionItem.ToolCall>()
        assertEquals("{}", calls.single().arguments)
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
    }

    @Test
    fun `complete calls without indexes remain separate`() = runTest {
        val fixture = KoogTestFixture(this)
        val session = fixture.session()
        session.features.require(SendsPrompts).send(fixture.request())
        fixture.executor.frames.send(StreamFrame.ToolCallComplete("one", "web_search", "{\"query\":\"one\"}", null))
        fixture.executor.frames.send(StreamFrame.ToolCallComplete("two", "web_search", "{\"query\":\"two\"}", null))
        fixture.executor.frames.send(StreamFrame.End("tool_calls"))
        fixture.executor.complete("Answer")
        runCurrent()
        val calls = session.features.require(SessionHistory).page().items.filterIsInstance<SessionItem.ToolCall>()
        assertEquals(listOf("one", "two"), calls.map { it.call.value })
    }
}
