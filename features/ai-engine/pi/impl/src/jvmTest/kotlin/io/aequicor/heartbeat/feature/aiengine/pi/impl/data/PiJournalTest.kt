package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PiJournalTest {
    @Test
    fun `thinking deltas retain their block order and final snapshot does not duplicate them`() = runTest {
        val journal = PiJournal()
        val turn = TurnId("turn")
        journal.record(record("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""), turn)
        journal.record(
            record(
                """{"type":"message_update",
                "assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"Answer"}}""",
            ),
            turn,
        )
        journal.record(
            record(
                """{"type":"message_update",
                "assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"Check"}}""",
            ),
            turn,
        )
        val streamed = assertIs<SessionItem.Message>(journal.page().items.single())
        assertEquals(listOf(ContentPart.Reasoning("Check"), ContentPart.Text("Answer")), streamed.parts)
        journal.record(
            record(
                """{"type":"message_end","message":{"role":"assistant","content":[
                {"type":"thinking","thinking":"Check","thinkingSignature":"opaque"},
                {"type":"text","text":"Answer"}]}}""",
            ),
            turn,
        )
        val finished = assertIs<SessionItem.Message>(journal.page().items.single())
        assertEquals(streamed.parts, finished.parts)
        assertEquals(streamed.info.id, finished.info.id)
        assertEquals(turn, finished.info.turn)
    }

    @Test
    fun deltaOnlyEventsReconstructTextAndReplayAfterAtomicPage() = runTest {
        val journal = PiJournal()
        journal.record(record("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""), null)
        val page = journal.page()
        journal.record(
            record(
                """{"type":"message_update",
                "assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hello"}}""",
            ),
            null,
        )
        journal.record(
            record(
                """{"type":"message_update",
                "assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":" world"}}""",
            ),
            null,
        )
        val current = assertIs<SessionItem.Message>(journal.page().items.single())
        assertEquals(listOf(ContentPart.Text("Hello world")), current.parts)
        assertEquals(2, current.info.revision)
        assertIs<SessionEvent.ItemUpserted>(journal.watch(page.checkpoint).first())
    }

    @Test
    fun expiredAndForeignCheckpointsInvalidateInsteadOfDroppingEvents() = runTest {
        val journal = PiJournal()
        val page = journal.page()
        repeat(
            520,
        ) { journal.record(record("""{"type":"message_start","message":{"role":"user","content":"text"}}"""), null) }
        assertIs<SessionEvent.HistoryInvalidated>(journal.watch(page.checkpoint).first())
        assertIs<SessionEvent.HistoryInvalidated>(journal.watch(HistoryCheckpoint("foreign")).first())
    }

    @Test
    fun historyPagesAreChronologicalAndHaveBothDirections() = runTest {
        val journal = PiJournal()
        repeat(
            6,
        ) { journal.record(record("""{"type":"message_start","message":{"role":"user","content":"text"}}"""), null) }
        val latest = journal.page(HistoryPageRequest(limit = 2))
        assertEquals(listOf(4L, 5L), latest.items.map { it.info.position })
        val older = journal.page(HistoryPageRequest(assertNotNull(latest.older), 2))
        assertEquals(listOf(2L, 3L), older.items.map { it.info.position })
        val newer = journal.page(HistoryPageRequest(assertNotNull(older.newer), 2))
        assertEquals(latest.items, newer.items)
        assertFailsWith<EngineException> { journal.page(HistoryPageRequest(HistoryCursor("foreign"))) }
    }

    @Test
    fun `restored transcript precedes live messages and settles stored tool calls`() = runTest {
        val journal = PiJournal()
        journal.restore(
            branch(
                """[
                {"role":"user","content":"Question"},
                {"role":"assistant","content":[
                    {"type":"text","text":"Checking"},
                    {"type":"toolCall","id":"done","name":"read","arguments":{}},
                    {"type":"toolCall","id":"broken","name":"edit","arguments":{}},
                    {"type":"toolCall","id":"lost","name":"bash","arguments":{}},
                    {"type":"toolCall","id":"","name":"blank","arguments":{}}]},
                {"role":"toolResult","toolCallId":"done","content":[{"type":"text","text":"file"}],"isError":false},
                {"role":"toolResult","toolCallId":"broken","content":[{"type":"text","text":"denied"}],"isError":true},
                {"role":"bashExecution","command":"ls"},
                {"role":"assistant","content":[{"type":"text","text":"Answer"}]}
                ]""",
            ),
        )
        journal.record(record("""{"type":"message_end","message":{"role":"user","content":"Next"}}"""), TurnId("t"))
        val items = journal.page().items
        assertEquals((0L..9L).toList(), items.map { it.info.position })
        assertEquals(MessageRole.User, assertIs<SessionItem.Message>(items[0]).role)
        assertEquals(
            listOf(ToolCallStatus.Succeeded, ToolCallStatus.Failed, ToolCallStatus.Cancelled),
            items.filterIsInstance<SessionItem.ToolCall>().map { it.status },
        )
        assertIs<SessionItem.ToolResult>(items[5])
        assertIs<SessionItem.UnsupportedItem>(items[7])
        assertEquals(listOf(ContentPart.Text("Answer")), assertIs<SessionItem.Message>(items[8]).parts)
        assertEquals(listOf(ContentPart.Text("Next")), assertIs<SessionItem.Message>(items[9]).parts)
        assertEquals(HistoryCoverage.Complete, journal.page().coverage)
        assertFailsWith<IllegalStateException> { journal.restore(PiStoredBranch(emptyList(), isComplete = true)) }
    }

    @Test
    fun `reused tool ids settle the call they answer`() = runTest {
        val journal = PiJournal()
        journal.restore(
            branch(
                """[
                {"role":"assistant","content":[{"type":"toolCall","id":"call_0","name":"read","arguments":{}}]},
                {"role":"toolResult","toolCallId":"call_0","content":"file","isError":false},
                {"role":"assistant","content":[{"type":"toolCall","id":"call_0","name":"bash","arguments":{}}]}
                ]""",
            ),
        )
        journal.record(
            record(
                """{"type":"message_end","message":{"role":"assistant",
                "content":[{"type":"toolCall","id":"call_0","name":"edit","arguments":{}}]}}""",
            ),
            TurnId("t"),
        )
        journal.record(record("""{"type":"tool_execution_end","toolCallId":"call_0","isError":true}"""), TurnId("t"))
        assertEquals(
            listOf(ToolCallStatus.Succeeded, ToolCallStatus.Cancelled, ToolCallStatus.Failed),
            journal.page().items.filterIsInstance<SessionItem.ToolCall>().map { it.status },
        )
    }

    @Test
    fun `new session history is complete`() = runTest {
        val journal = PiJournal()
        journal.record(record("""{"type":"message_end","message":{"role":"user","content":"Hi"}}"""), TurnId("t"))
        assertEquals(HistoryCoverage.Complete, journal.page().coverage)
    }

    @Test
    fun `incomplete restored branch is omitted and only new live items enter the partial journal`() = runTest {
        val journal = PiJournal()
        journal.restore(branch("""[{"role":"assistant","content":"Tail"}]""", isComplete = false))
        val page = journal.page()
        assertEquals(emptyList(), page.items)
        assertEquals(HistoryCoverage.Partial, page.coverage)

        val turn = TurnId("continued")
        journal.record(record("""{"type":"message_end","message":{"role":"user","content":"Next"}}"""), turn)

        val item = assertIs<SessionItem.Message>(journal.page().items.single())
        assertEquals(listOf(ContentPart.Text("Next")), item.parts)
        assertEquals(turn, item.info.turn)
        assertEquals(HistoryCoverage.Partial, journal.page().coverage)
        assertEquals(item, assertIs<SessionEvent.ItemUpserted>(journal.watch(page.checkpoint).first()).item)
    }

    private fun record(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun branch(json: String, isComplete: Boolean = true) =
        PiStoredBranch(Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }, isComplete)
}
