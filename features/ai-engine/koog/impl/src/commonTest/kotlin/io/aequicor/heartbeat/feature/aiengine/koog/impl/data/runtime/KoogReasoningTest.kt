package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KoogReasoningTest {
    @Test
    fun `reasoning streams before the answer and completed blocks do not duplicate deltas`() = runTest {
        val fixture = KoogTestFixture(this)
        val session = fixture.session()
        session.features.require(SendsPrompts).send(fixture.request())
        fixture.executor.frames.send(StreamFrame.ReasoningDelta(text = "Check ", index = 0))
        fixture.executor.frames.send(StreamFrame.ReasoningDelta(text = "the files", index = 0))
        runCurrent()
        assertIs<ActiveSessionState.Running>(session.state.value)
        val streaming = assertIs<SessionItem.Message>(session.features.require(SessionHistory).page().items.last())
        assertEquals(listOf(ContentPart.Reasoning("Check the files")), streaming.parts)

        fixture.executor.frames.send(
            StreamFrame.ReasoningComplete("reasoning", listOf("Check the files"), null, "opaque", 0),
        )
        fixture.executor.frames.send(StreamFrame.TextDelta("Answer", index = 1))
        fixture.executor.frames.send(StreamFrame.TextComplete("Answer", index = 1))
        fixture.executor.frames.send(StreamFrame.End("stop"))
        runCurrent()
        val reply = assertIs<SessionItem.Message>(session.features.require(SessionHistory).page().items.last())
        assertEquals(listOf(ContentPart.Reasoning("Check the files"), ContentPart.Text("Answer")), reply.parts)
        assertEquals(streaming.info.id, reply.info.id)
        assertTrue(reply.info.revision > streaming.info.revision)
        session.close()
        val resumed = fixture.runtime().attach(session.ref, ResumeSessionRequest(fixture.target))
        assertEquals(reply, resumed.features.require(SessionHistory).page().items.last())
    }

    @Test
    fun `public summaries replace partial reasoning while opaque signatures never become content`() = runTest {
        val fixture = KoogTestFixture(this)
        val session = fixture.session()
        session.features.require(SendsPrompts).send(fixture.request())
        fixture.executor.frames.send(StreamFrame.ReasoningDelta(summary = "Check ", index = 0))
        fixture.executor.frames.send(
            StreamFrame.ReasoningComplete("reasoning", emptyList(), listOf("Check the public API"), "opaque", 0),
        )
        fixture.executor.complete("Ready")
        runCurrent()
        val reply = assertIs<SessionItem.Message>(session.features.require(SessionHistory).page().items.last())
        assertEquals(listOf(ContentPart.Reasoning("Check the public API"), ContentPart.Text("Ready")), reply.parts)
        session.features.require(SendsPrompts).send(fixture.request("follow-up"))
        fixture.executor.complete("Next")
        runCurrent()
        assertTrue(fixture.executor.prompts.last().messages.none { "Check the public API" in it.textContent() })
    }

    @Test
    fun `encrypted-only reasoning does not produce a visible empty message`() = runTest {
        val fixture = KoogTestFixture(this)
        val session = fixture.session()
        session.features.require(SendsPrompts).send(fixture.request())
        fixture.executor.frames.send(StreamFrame.ReasoningComplete("hidden", emptyList(), null, "opaque", 0))
        runCurrent()
        assertEquals(1, session.features.require(SessionHistory).page().items.size)
        fixture.executor.complete("Answer")
        runCurrent()
        val reply = assertIs<SessionItem.Message>(session.features.require(SessionHistory).page().items.last())
        assertEquals(listOf(ContentPart.Text("Answer")), reply.parts)
    }
}
