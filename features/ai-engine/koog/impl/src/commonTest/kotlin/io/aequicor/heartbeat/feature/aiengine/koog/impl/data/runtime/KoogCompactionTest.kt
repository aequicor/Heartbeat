package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.MessagePart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoticeKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogContextSummary
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KoogCompactionTest {
    @Test
    fun `compaction summarizes without tools and later prompts start from the summary`() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        val send = session.features.require(SendsPrompts)
        send.send(text("first", "Investigate the parser"))
        f.executor.complete("Found the bug")
        runCurrent()

        send.send(PromptRequest(RequestId("compact"), emptyList(), compaction = ContextCompaction("keep files")))
        f.executor.complete("SUMMARY")
        runCurrent()

        val summaryInput = f.executor.prompts.last().text()
        assertTrue(f.executor.tools.last().isEmpty())
        assertTrue("Investigate the parser" in summaryInput && "keep files" in summaryInput)
        val compacted = assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn
        assertEquals(TurnOutcome.Completed, compacted?.outcome)
        val items = session.features.require(SessionHistory).page().items
        assertEquals(NoticeKind.ContextCompacted, assertIs<SessionItem.Notice>(items.last()).kind)
        assertEquals("SUMMARY", f.records.values.values.single().context?.summary)

        send.send(text("next", "Fix it"))
        f.executor.complete("Fixed")
        runCurrent()

        val next = f.executor.prompts.last().text()
        assertTrue("SUMMARY" in next && "Fix it" in next)
        assertFalse("Investigate the parser" in next)
        assertEquals(5, session.features.require(SessionHistory).page().items.size)
    }

    @Test
    fun `failed compaction keeps the context unchanged`() = runTest {
        val f = KoogTestFixture(this)
        val session = f.session()
        f.executor.nextFailure = IllegalStateException("provider down")

        session.features.require(SendsPrompts)
            .send(PromptRequest(RequestId("compact"), emptyList(), compaction = ContextCompaction()))
        runCurrent()

        val outcome = assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome
        assertIs<EngineFailure>(assertIs<TurnOutcome.Failed>(outcome).failure)
        assertNull(f.records.values.values.single().context)
        assertTrue(session.features.require(SessionHistory).page().items.none { it is SessionItem.Notice })
    }

    @Test
    fun `items before the boundary leave the model context`() {
        val notice = koogCompactedNotice(1, TurnId("t"))
        val earlier = SessionItem.Message(
            notice.info.copy(id = ItemId("m"), position = 0),
            MessageRole.User,
            listOf(ContentPart.Text("old")),
        )
        val context = KoogContextSummary(notice.info.id, "s")

        assertEquals(emptyList(), listOf(earlier, notice).koogContextItems(context))
        assertEquals(listOf(earlier, notice), listOf(earlier, notice).koogContextItems(null))
    }

    private fun text(id: String, value: String) = PromptRequest(RequestId(id), listOf(ContentPart.Text(value)))

    private fun Prompt.text(): String = messages.flatMap { it.parts }.filterIsInstance<MessagePart.Text>()
        .joinToString("\n") { it.text }
}
