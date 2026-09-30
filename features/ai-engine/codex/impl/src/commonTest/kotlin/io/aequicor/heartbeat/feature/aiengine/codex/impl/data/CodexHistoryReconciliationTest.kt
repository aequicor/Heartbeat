package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexHistoryReconciliationTest {
    @Test
    fun `reattaching after an unseen native turn preserves observed items with partial coverage`() = runTest {
        val fixture = Fixture(this)
        val original = fixture.completedSession()
        val before = original.feature(SessionHistory).page()
        fixture.threadTurns = listOf(
            nativeTurn(),
            nativeTurn("external-turn", listOf(message("external-answer", "Unobserved answer"))),
        )

        val resumed = fixture.runtime.attach(original.ref, ResumeSessionRequest(fixture.target))
        val page = resumed.feature(SessionHistory).page()

        assertEquals(HistoryCoverage.Partial, page.coverage)
        assertEquals(before.items, page.items)
        assertIs<ActiveSessionState.Ready>(resumed.state.value)
        assertEquals(
            JsonPrimitive(true),
            fixture.wire.written.single { it.text("method") == "thread/read" }.obj("params")["includeTurns"],
        )
        fixture.runtime.close()
    }

    @Test
    fun `reattaching after known native item changes preserves its observed content with partial coverage`() = runTest {
        val fixture = Fixture(this)
        val original = fixture.completedSession()
        val before = original.feature(SessionHistory).page()
        fixture.threadTurns = listOf(nativeTurn(items = listOf(message(text = "Changed answer"))))

        val resumed = fixture.runtime.attach(original.ref, ResumeSessionRequest(fixture.target))
        val page = resumed.feature(SessionHistory).page()

        assertEquals(HistoryCoverage.Partial, page.coverage)
        assertEquals(before.items, page.items)
        assertEquals(
            listOf(ContentPart.Text("Original answer")),
            assertIs<SessionItem.Message>(page.items.single()).parts,
        )
        assertIs<ActiveSessionState.Ready>(resumed.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `reattaching with an unchanged native snapshot keeps complete observed history`() = runTest {
        val fixture = Fixture(this)
        val original = fixture.completedSession()
        val before = original.feature(SessionHistory).page()
        fixture.threadTurns = listOf(nativeTurn())

        val resumed = fixture.runtime.attach(original.ref, ResumeSessionRequest(fixture.target))
        val page = resumed.feature(SessionHistory).page()

        assertEquals(HistoryCoverage.Complete, page.coverage)
        assertEquals(before.items, page.items)
        assertEquals(original.state.value, resumed.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `failed history audit preserves observed items and returns a usable partial history session`() = runTest {
        val fixture = Fixture(this)
        val original = fixture.completedSession()
        val before = original.feature(SessionHistory).page()
        val handler = fixture.wire.handler
        fixture.wire.handler = { message ->
            if (message.text("method") == "thread/read") fixture.wire.error(message) else handler(message)
        }

        val resumed = fixture.runtime.attach(original.ref, ResumeSessionRequest(fixture.target))
        val page = resumed.feature(SessionHistory).page()

        assertEquals(HistoryCoverage.Partial, page.coverage)
        assertEquals(before.items, page.items)
        assertIs<ActiveSessionState.Ready>(resumed.state.value)
        val next = resumed.feature(SendsPrompts).send(Prompt.copy(id = RequestId("next-prompt")))
        assertEquals(next, assertIs<ActiveSessionState.Running>(resumed.state.value).turn.id)
        fixture.runtime.close()
    }

    private suspend fun Fixture.completedSession(): ActiveSession {
        val session = open()
        session.feature(SendsPrompts).send(Prompt)
        event("item/completed", "turnId" to "native-turn".json(), "item" to message())
        event("turn/completed", "turn" to nativeTurn())
        test.runCurrent()
        assertIs<ActiveSessionState.Ready>(session.state.value)
        val page = session.feature(SessionHistory).page()
        assertEquals(HistoryCoverage.Complete, page.coverage)
        assertEquals(
            listOf(ContentPart.Text("Original answer")),
            assertIs<SessionItem.Message>(page.items.single()).parts,
        )
        return session
    }

    private fun nativeTurn(id: String = "native-turn", items: List<JsonObject> = listOf(message())): JsonObject = json(
        "id" to id.json(),
        "status" to "completed".json(),
        "itemsView" to "full".json(),
        "items" to JsonArray(items),
    )

    private fun message(id: String = "answer", text: String = "Original answer"): JsonObject = json(
        "id" to id.json(),
        "type" to "agentMessage".json(),
        "text" to text.json(),
    )
}
