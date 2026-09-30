@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CodexHistoryResumeTest {
    @Test
    fun `unavailable reconciliation preserves live items when a native item was missed`() = runTest {
        val fixture = Fixture(this)
        val handler = fixture.wire.handler
        fixture.wire.handler = { if (it.text("method") != "turn/interrupt") handler(it) }
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        val live = json("id" to "live".json(), "type" to "agentMessage".json(), "text" to "Observed".json())
        fixture.event("item/completed", "turnId" to "native-turn".json(), "item" to live)
        runCurrent()
        val history = session.feature(SessionHistory)
        val before = history.page().items
        fixture.threadTurns = listOf(
            json(
                "id" to "native-turn".json(),
                "status" to "completed".json(),
                "itemsView" to "full".json(),
                "items" to JsonArray(
                    listOf(
                        live,
                        json("id" to "missed".json(), "type" to "agentMessage".json(), "text" to "New".json()),
                    ),
                ),
            ),
        )
        session.feature(CancelsTurns).cancel(turn)
        advanceTimeBy(INTERRUPT_TIMEOUT)
        runCurrent()
        assertIs<ActiveSessionState.Ready>(session.state.value)
        assertEquals(HistoryCoverage.Partial, history.page().coverage)
        assertEquals(before, history.page().items)
        fixture.runtime.close()
    }

    @Test
    fun `canonical full resumed thread seeds stable native items and covers the whole history`() = runTest {
        val fixture = Fixture(this)
        fixture.resumedHistoryMode = "paginated"
        fixture.resumedTurns = JsonArray(
            listOf(
                json(
                    "id" to "stored-turn".json(),
                    "status" to "completed".json(),
                    "itemsView" to "full".json(),
                    "items" to JsonArray(
                        listOf(
                            json(
                                "id" to "prompt".json(),
                                "type" to "userMessage".json(),
                                "content" to JsonArray(listOf(json("text" to "hi".json()))),
                            ),
                            json("id" to "answer".json(), "type" to "agentMessage".json(), "text" to "hello".json()),
                        ),
                    ),
                ),
            ),
        )
        val session = fixture.runtime.attach(fixture.storedRef(), ResumeSessionRequest(fixture.target))
        val page = session.feature(SessionHistory).page()
        assertEquals(listOf("prompt", "answer"), page.items.map { it.info.id.value })
        assertEquals(HistoryCoverage.Complete, page.coverage)
        fixture.runtime.close()
    }

    @Test
    fun `resumed thread without native turns reports partial history`() = runTest {
        val fixture = Fixture(this)
        fixture.resumedTurns = null
        val session = fixture.runtime.attach(fixture.storedRef(), ResumeSessionRequest(fixture.target))
        val page = session.feature(SessionHistory).page()
        assertEquals(emptyList(), page.items)
        assertEquals(HistoryCoverage.Partial, page.coverage)
        fixture.runtime.close()
    }

    @Test
    fun `resumed thread with empty native turns reports partial history`() = runTest {
        val fixture = Fixture(this)
        fixture.resumedTurns = JsonArray(emptyList())
        val session = fixture.runtime.attach(fixture.storedRef(), ResumeSessionRequest(fixture.target))
        assertEquals(HistoryCoverage.Partial, session.feature(SessionHistory).page().coverage)
        fixture.runtime.close()
    }

    @Test
    fun `resumed turns with summarized items omit the whole replay to preserve the saved transcript`() = runTest {
        val fixture = Fixture(this)
        fixture.resumedHistoryMode = "paginated"
        fixture.resumedTurns = JsonArray(
            listOf(
                json("id" to "full-turn".json(), "itemsView" to "full".json(), "items" to JsonArray(emptyList())),
                json(
                    "id" to "summary-turn".json(),
                    "itemsView" to "summary".json(),
                    "items" to JsonArray(
                        listOf(json("id" to "answer".json(), "type" to "agentMessage".json(), "text" to "hi".json())),
                    ),
                ),
            ),
        )
        val session = fixture.runtime.attach(fixture.storedRef(), ResumeSessionRequest(fixture.target))
        val page = session.feature(SessionHistory).page()
        assertEquals(emptyList(), page.items)
        assertEquals(HistoryCoverage.Partial, page.coverage)
        fixture.runtime.close()
    }

    @Test
    fun `legacy full replay never reseeds earlier messages under synthetic ids`() = runTest {
        for ((index, mode) in listOf(null, "legacy").withIndex()) {
            val fixture = Fixture(this)
            fixture.resumedHistoryMode = mode
            fixture.resumedTurns = JsonArray(
                listOf(
                    json(
                        "id" to "stored-turn".json(),
                        "itemsView" to "full".json(),
                        "items" to JsonArray(
                            listOf(
                                json(
                                    "id" to "item-$index".json(),
                                    "type" to "agentMessage".json(),
                                    "text" to "Already saved under a live id".json(),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val session = fixture.runtime.attach(fixture.storedRef(), ResumeSessionRequest(fixture.target))
            val history = session.feature(SessionHistory)
            assertEquals(emptyList(), history.page().items)
            assertEquals(HistoryCoverage.Partial, history.page().coverage)
            session.feature(SendsPrompts).send(Prompt)
            fixture.event(
                "item/completed",
                "turnId" to "native-turn".json(),
                "item" to json("id" to "new-live-id".json(), "type" to "agentMessage".json(), "text" to "New".json()),
            )
            runCurrent()
            assertEquals(listOf("new-live-id"), history.page().items.map { it.info.id.value })
            assertEquals(HistoryCoverage.Partial, history.page().coverage)
            fixture.runtime.close()
        }
    }

    @Test
    fun `canonical replay without a full items view or items array stays empty and partial`() = runTest {
        for (turn in listOf(
            json("id" to "turn".json(), "items" to JsonArray(emptyList())),
            json("id" to "turn".json(), "itemsView" to "full".json()),
        )) {
            val fixture = Fixture(this)
            fixture.resumedHistoryMode = "paginated"
            fixture.resumedTurns = JsonArray(listOf(turn))
            val session = fixture.runtime.attach(fixture.storedRef(), ResumeSessionRequest(fixture.target))
            val page = session.feature(SessionHistory).page()
            assertEquals(emptyList(), page.items)
            assertEquals(HistoryCoverage.Partial, page.coverage)
            fixture.runtime.close()
        }
    }

    @Test
    fun `thread response with turns of the wrong type is a protocol failure`() = runTest {
        for (method in listOf("thread/start", "thread/resume")) {
            val fixture = Fixture(this)
            val handler = fixture.wire.handler
            fixture.wire.handler = { message ->
                if (message.text("method") == method) {
                    fixture.wire.reply(
                        message,
                        json(
                            "thread" to json("id" to "thread".json(), "turns" to json()),
                            "approvalPolicy" to "never".json(),
                            "sandbox" to json("type" to "readOnly".json(), "networkAccess" to JsonPrimitive(false)),
                        ),
                    )
                } else {
                    handler(message)
                }
            }
            val failure = assertFailsWith<EngineException> {
                if (method == "thread/start") {
                    fixture.open()
                } else {
                    fixture.runtime.attach(fixture.storedRef(), ResumeSessionRequest(fixture.target))
                }
            }
            assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), failure.failure)
            fixture.runtime.close()
        }
    }

    @Test
    fun `canonical full items with pending turn or item pages cannot replace the saved transcript`() = runTest {
        for (cursor in listOf("turnsBackwardsCursor", "itemsBackwardsCursor")) {
            val fixture = Fixture(this)
            val handler = fixture.wire.handler
            fixture.wire.handler = { message ->
                if (message.text("method") == "thread/resume") {
                    fixture.wire.reply(
                        message,
                        json(
                            cursor to "older".json(),
                            "approvalPolicy" to "never".json(),
                            "sandbox" to json("type" to "readOnly".json(), "networkAccess" to JsonPrimitive(false)),
                            "thread" to json(
                                "id" to "thread".json(),
                                "historyMode" to "paginated".json(),
                                "turns" to JsonArray(
                                    listOf(
                                        json(
                                            "id" to "turn".json(),
                                            "itemsView" to "full".json(),
                                            "items" to JsonArray(
                                                listOf(json("id" to "item".json(), "type" to "agentMessage".json())),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    )
                } else {
                    handler(message)
                }
            }
            val session = fixture.runtime.attach(fixture.storedRef(), ResumeSessionRequest(fixture.target))
            val page = session.feature(SessionHistory).page()
            assertEquals(HistoryCoverage.Partial, page.coverage)
            assertEquals(emptyList(), page.items)
            fixture.runtime.close()
        }
    }

    @Test
    fun `new thread covers the whole history`() = runTest {
        val fixture = Fixture(this)
        assertEquals(HistoryCoverage.Complete, fixture.open().feature(SessionHistory).page().coverage)
        fixture.runtime.close()
    }

    private fun Fixture.storedRef() = SessionRef(target.engine, environment.config.historySource, "thread")

    private companion object {
        const val INTERRUPT_TIMEOUT = 31_000L
    }
}
