@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class CodexUnresolvedTurnTest {
    @Test
    fun `incomplete native snapshots retain exact turn until its completion event`() = runTest {
        val snapshots = listOf(
            json("id" to "thread".json()),
            thread(emptyList()),
            thread(listOf(native("foreign", "completed"))),
            thread(listOf(json("id" to "native-turn".json()))),
            thread(listOf(native("native-turn", "unrecognized"))),
        )
        for (snapshot in snapshots) {
            val fixture = Fixture(this)
            val base = fixture.wire.handler
            fixture.wire.handler = {
                when (it.text("method")) {
                    "turn/interrupt" -> Unit
                    "thread/read" -> fixture.wire.reply(it, json("thread" to snapshot))
                    else -> base(it)
                }
            }
            val session = fixture.open()
            val turn = session.feature(SendsPrompts).send(Prompt)
            session.feature(CancelsTurns).cancel(turn)
            advanceTimeBy(INTERRUPT_TIMEOUT)
            runCurrent()
            val unresolved = assertIs<ActiveSessionState.Unavailable>(session.state.value)
            assertEquals(turn, unresolved.activeTurn?.id)
            assertEquals(Prompt.id, unresolved.activeTurn?.request)
            assertNull(unresolved.lastTurn)
            assertFailsWith<EngineException> {
                session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("retry")))
            }
            assertEquals(1, fixture.wire.written.count { it.text("method") == "turn/start" })

            fixture.event("turn/completed", "turn" to native("native-turn", "interrupted"))
            runCurrent()
            val completed = assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn
            assertEquals(turn, completed?.id)
            assertEquals(Prompt.id, completed?.request)
            assertEquals(TurnOutcome.Cancelled, completed?.outcome)
        }
    }

    @Test
    fun `mapped terminal snapshot settles the exact request after interrupt timeout`() = runTest {
        for (status in listOf("completed", "interrupted", "failed")) {
            val fixture = Fixture(this)
            val base = fixture.wire.handler
            fixture.wire.handler = { if (it.text("method") != "turn/interrupt") base(it) }
            fixture.threadTurns = listOf(native("native-turn", status))
            val session = fixture.open()
            val turn = session.feature(SendsPrompts).send(Prompt)
            session.feature(CancelsTurns).cancel(turn)
            advanceTimeBy(INTERRUPT_TIMEOUT)
            runCurrent()
            val completed = assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn
            assertEquals(turn, completed?.id)
            assertEquals(Prompt.id, completed?.request)
            when (status) {
                "completed" -> assertEquals(TurnOutcome.Completed, completed?.outcome)
                "interrupted" -> assertEquals(TurnOutcome.Cancelled, completed?.outcome)
                "failed" -> assertIs<TurnOutcome.Failed>(completed?.outcome)
            }
            assertEquals(1, fixture.wire.written.count { it.text("method") == "turn/start" })
        }
    }

    @Test
    fun `explicit reconciliation runs the machine recheck and waits for it`() = runTest {
        val unresolved = unresolved()
        val gate = CompletableDeferred<Unit>()
        unresolved.beforeRead = { gate.await() }
        unresolved.snapshot = thread(listOf(native("native-turn", "completed")))
        val reads = unresolved.reads()
        val synchronizing = async { unresolved.session.feature(ReconcilesSession).synchronize() }
        runCurrent()
        assertFalse(synchronizing.isCompleted)
        assertEquals(reads + 1, unresolved.reads())
        gate.complete(Unit)
        synchronizing.await()
        val completed = assertIs<ActiveSessionState.Ready>(unresolved.session.state.value).lastTurn
        assertEquals(unresolved.turn, completed?.id)
        assertEquals(TurnOutcome.Completed, completed?.outcome)
        assertEquals(reads + 1, unresolved.reads())
        unresolved.fixture.runtime.close()
    }

    @Test
    fun `explicit reconciliation of a still unresolved turn reports its failure`() = runTest {
        val unresolved = unresolved()
        assertFailsWith<EngineException> { unresolved.session.feature(ReconcilesSession).synchronize() }
        val state = assertIs<ActiveSessionState.Unavailable>(unresolved.session.state.value)
        assertEquals(unresolved.turn, state.activeTurn?.id)
        unresolved.fixture.runtime.close()
    }

    @Test
    fun `cancel of a turn found running again still interrupts it`() = runTest {
        val unresolved = unresolved()
        unresolved.snapshot = thread(listOf(native("native-turn", "inProgress")))
        unresolved.session.feature(CancelsTurns).cancel(unresolved.turn)
        runCurrent()
        val state = assertIs<ActiveSessionState.Interrupting>(unresolved.session.state.value)
        assertEquals(unresolved.turn, state.turn.id)
        assertEquals(2, unresolved.fixture.wire.written.count { it.text("method") == "turn/interrupt" })
        unresolved.fixture.runtime.close()
    }

    /** A running turn whose interrupt timed out while native history could not resolve it. */
    private class Unresolved(val fixture: Fixture) {
        var snapshot: JsonObject = json("id" to "thread".json(), "turns" to JsonArray(emptyList()))
        var beforeRead: suspend () -> Unit = {}
        lateinit var session: ActiveSession
        lateinit var turn: TurnId

        fun reads(): Int = fixture.wire.written.count { it.text("method") == "thread/read" }
    }

    private suspend fun TestScope.unresolved(): Unresolved {
        val unresolved = Unresolved(Fixture(this))
        val fixture = unresolved.fixture
        val base = fixture.wire.handler
        fixture.wire.handler = {
            when (it.text("method")) {
                "turn/interrupt" -> Unit

                "thread/read" -> {
                    unresolved.beforeRead()
                    fixture.wire.reply(it, json("thread" to unresolved.snapshot))
                }

                else -> base(it)
            }
        }
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        session.feature(CancelsTurns).cancel(turn)
        advanceTimeBy(INTERRUPT_TIMEOUT)
        runCurrent()
        assertEquals(turn, assertIs<ActiveSessionState.Unavailable>(session.state.value).activeTurn?.id)
        unresolved.session = session
        unresolved.turn = turn
        return unresolved
    }

    private fun thread(turns: List<JsonObject>): JsonObject = json("id" to "thread".json(), "turns" to JsonArray(turns))

    private fun native(id: String, status: String): JsonObject = json("id" to id.json(), "status" to status.json())

    private companion object {
        const val INTERRUPT_TIMEOUT = 31_000L
    }
}
