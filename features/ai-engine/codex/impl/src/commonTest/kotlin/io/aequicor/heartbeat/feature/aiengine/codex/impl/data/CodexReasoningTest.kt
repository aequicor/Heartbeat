package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexReasoningTest {
    @Test
    fun `summary notifications stream in indexed order and completion replaces without duplication`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        // The fixture's app-server returns this native id; send() returns the facade's independent turn id.
        val nativeTurn = "native-turn".json()
        fixture.event("item/started", "turnId" to nativeTurn, "item" to reasoning())
        fixture.event(
            "item/reasoning/summaryTextDelta",
            "turnId" to nativeTurn,
            "itemId" to "reasoning".json(),
            "summaryIndex" to JsonPrimitive(1),
            "delta" to "Then test".json(),
        )
        fixture.event(
            "item/reasoning/summaryTextDelta",
            "turnId" to nativeTurn,
            "itemId" to "reasoning".json(),
            "summaryIndex" to JsonPrimitive(0),
            "delta" to "Read ".json(),
        )
        fixture.event(
            "item/reasoning/summaryTextDelta",
            "turnId" to nativeTurn,
            "itemId" to "reasoning".json(),
            "summaryIndex" to JsonPrimitive(0),
            "delta" to "the API".json(),
        )
        runCurrent()
        val streamed = assertIs<SessionItem.Message>(session.feature(SessionHistory).page().items.single())
        assertEquals(listOf(ContentPart.Reasoning("Read the API"), ContentPart.Reasoning("Then test")), streamed.parts)
        assertEquals(turn, streamed.info.turn)
        fixture.event(
            "item/completed",
            "turnId" to nativeTurn,
            "item" to reasoning("Read the public API", "Then test"),
        )
        runCurrent()
        val completed = assertIs<SessionItem.Message>(session.feature(SessionHistory).page().items.single())
        assertEquals(
            listOf(ContentPart.Reasoning("Read the public API"), ContentPart.Reasoning("Then test")),
            completed.parts,
        )
        assertEquals(streamed.info.id, completed.info.id)
        assertTrue(completed.info.revision > streamed.info.revision)
    }

    @Test
    fun `historical reasoning exposes summaries and keeps content-only protocol items unsupported`() = runTest {
        val history = CodexHistory()
        history.nativeItem(reasoning(), TurnId("turn"))
        assertIs<SessionItem.UnsupportedItem>(history.page().items.single())
        history.nativeItem(reasoning("Review the public API"), TurnId("turn"))
        val reply = assertIs<SessionItem.Message>(history.page().items.single())
        assertEquals(listOf(ContentPart.Reasoning("Review the public API")), reply.parts)
    }

    @Test
    fun `hosted web search runs until completed and keeps its query`() = runTest {
        val history = CodexHistory()
        val started = json("id" to "search".json(), "type" to "webSearch".json(), "query" to "".json())
        history.nativeItem(started, TurnId("turn"), isStarted = true)
        assertEquals(ToolCallStatus.Running, assertIs<SessionItem.ToolCall>(history.page().items.single()).status)
        history.nativeItem(
            json("id" to "search".json(), "type" to "webSearch".json(), "query" to "kotlin 2.3".json()),
            TurnId("turn"),
        )
        val call = assertIs<SessionItem.ToolCall>(history.page().items.single())
        assertEquals(ToolCallStatus.Succeeded, call.status)
        assertEquals("codex_web_search", call.name)
        assertTrue("kotlin 2.3" in call.arguments)
    }

    private fun reasoning(vararg summaries: String) = json(
        "id" to "reasoning".json(),
        "type" to "reasoning".json(),
        "summary" to JsonArray(summaries.map { it.json() }),
        "content" to JsonArray(listOf("Unexposed native content".json())),
        "encrypted_content" to "opaque".json(),
    )
}
