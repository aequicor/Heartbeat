package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
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
import kotlinx.serialization.json.JsonArray
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
            Json.parseToJsonElement(
                """[
                {"role":"user","content":"Question"},
                {"role":"assistant","content":[
                    {"type":"text","text":"Checking"},
                    {"type":"toolCall","id":"done","name":"read","arguments":{}},
                    {"type":"toolCall","id":"lost","name":"bash","arguments":{}}]},
                {"role":"toolResult","toolCallId":"done","content":[{"type":"text","text":"file"}],"isError":false},
                {"role":"assistant","content":[{"type":"text","text":"Answer"}]}
                ]""",
            ).jsonArray,
        )
        journal.record(record("""{"type":"message_end","message":{"role":"user","content":"Next"}}"""), TurnId("t"))
        val items = journal.page().items
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L), items.map { it.info.position })
        assertEquals(MessageRole.User, assertIs<SessionItem.Message>(items[0]).role)
        assertEquals(
            listOf(ToolCallStatus.Succeeded, ToolCallStatus.Cancelled),
            items.filterIsInstance<SessionItem.ToolCall>().map { it.status },
        )
        assertIs<SessionItem.ToolResult>(items[4])
        assertEquals(listOf(ContentPart.Text("Answer")), assertIs<SessionItem.Message>(items[5]).parts)
        assertEquals(listOf(ContentPart.Text("Next")), assertIs<SessionItem.Message>(items[6]).parts)
        assertFailsWith<IllegalStateException> { journal.restore(JsonArray(emptyList())) }
    }

    private fun record(json: String) = Json.parseToJsonElement(json).jsonObject
}
