package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.answerOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class PiAnswerCorrelationsTest {
    @Test
    fun `serialized correlations restore exact answers across later native messages and key order changes`() = runTest {
        val records = MemoryPiTurnRecords()
        val journal = PiTurnJournal(records, RuntimeRef, Route, "owner")
        val answers = PiAnswerCorrelations(records, RuntimeRef, Route, "owner")
        val first = turn("first")
        val second = turn("second")
        val one = assistant("first answer", 1)
        val two = assistant("second answer", 2)
        journal.begin(first, TrustLevel.Ask)
        answers.record(ended(one), first.id)
        journal.finish(first.id, TurnOutcome.Completed, answers.pending(first.id))
        journal.begin(second, TrustLevel.Ask)
        answers.record(ended(two), second.id)
        journal.finish(second.id, TurnOutcome.Completed, answers.pending(second.id))
        val restored = Json.decodeFromString<PiTurnSnapshot>(Json.encodeToString(records.get(RuntimeRef)))
        val history = PiJournal()
        val reordered = JsonObject(one.entries.reversed().associate { it.toPair() })
        history.restore(
            PiStoredBranch(listOf(reordered, two, assistant("foreign tail", 3)), true),
            restored.answerTurns,
        )
        val items = history.page().items
        assertEquals("first answer", answerOf(items, first.id, 100, isMarkedOnly = true))
        assertEquals("second answer", answerOf(items, second.id, 100, isMarkedOnly = true))
        assertNull(answerOf(items, TurnId("foreign"), 100, isMarkedOnly = true))
    }

    @Test
    fun `identical native message observed in different turns becomes permanently ambiguous`() = runTest {
        val records = MemoryPiTurnRecords()
        val journal = PiTurnJournal(records, RuntimeRef, Route, "owner")
        val answers = PiAnswerCorrelations(records, RuntimeRef, Route, "owner")
        val message = assistant("same", 1)
        for (id in listOf("first", "second", "first")) {
            val turn = turn(id)
            journal.begin(turn, TrustLevel.Ask)
            answers.record(ended(message), turn.id)
            journal.finish(turn.id, TurnOutcome.Completed, answers.pending(turn.id))
        }
        val stored = answers.restore()
        assertEquals(setOf(piAnswerKey(message)), stored.keys)
        assertNull(stored[piAnswerKey(message)])
        val history = PiJournal()
        history.restore(PiStoredBranch(listOf(message), true), stored)
        assertNull(answerOf(history.page().items, TurnId("first"), 100, isMarkedOnly = true))
    }

    @Test
    fun `duplicate stored messages and legacy unknown messages are not assigned by position`() = runTest {
        val message = assistant("duplicate", 1)
        val turn = TurnId("known")
        val history = PiJournal()
        history.restore(
            PiStoredBranch(listOf(message, assistant("unknown", 2), message), true),
            mapOf(checkNotNull(piAnswerKey(message)) to turn),
        )
        assertNull(answerOf(history.page().items, turn, 100, isMarkedOnly = true))
        assertFalse(history.page().items.any { it.info.turn != null })
    }

    @Test
    fun `stale events cannot annotate a replacement request and foreign credentials cannot read correlations`() =
        runTest {
            val records = MemoryPiTurnRecords()
            val journal = PiTurnJournal(records, RuntimeRef, Route, "owner")
            val answers = PiAnswerCorrelations(records, RuntimeRef, Route, "owner")
            val first = turn("first")
            journal.begin(first, TrustLevel.Ask)
            journal.finish(first.id, TurnOutcome.Completed, answers.pending(first.id))
            val second = turn("second")
            journal.begin(second, TrustLevel.Ask)
            answers.record(ended(assistant("late", 1)), first.id)
            assertEquals(emptyMap(), answers.restore())
            assertEquals(second, journal.restore()?.active?.turn)
            val foreign = PiAnswerCorrelations(records, RuntimeRef, Route, "other")
            assertFailsWith<EngineException> { foreign.restore() }
            assertEquals(emptyMap(), answers.pending(second.id))
            assertEquals(emptyMap(), answers.restore())
        }

    @Test
    fun `runtime reopening restores the exact accepted answer without borrowing a later native tail`() = runTest {
        val records = MemoryPiTurnRecords()
        val first = runtimeFixture(this, records)
        first.processes.configure = { it.promptAck.complete(JsonObject(emptyMap())) }
        val session = first.runtime.create(CreateSessionRequest(RuntimeTarget))
        val turn = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts))
            .feature.send(prompt("request"))
        val answer = assistant("owned answer", 1)
        first.processes.connections.single().event(ended(answer))
        first.processes.connections.single().event(record("""{"type":"agent_settled"}"""))
        first.runtime.close()
        val second = runtimeFixture(this, records)
        second.processes.configure = {
            it.entries = """{"leafId":"later","entries":[
                {"id":"owned","parentId":null,"type":"message","message":$answer},
                {"id":"later","parentId":"owned","type":"message","message":${assistant("other answer", 2)}}
            ]}"""
        }
        val restored = second.runtime.attach(session.ref, RuntimeRequest)
        val history = assertIs<FeatureAccess.Available<SessionHistory>>(
            restored.features.resolve(SessionHistory),
        ).feature
        assertEquals("owned answer", answerOf(history.page().items, turn, 100, isMarkedOnly = true))
        second.runtime.close()
    }

    @Test
    fun `failed terminal write preserves both result and answer correlation for one atomic retry`() = runTest {
        val records = GatedPiTurnRecords()
        val fixture = fixture(this, turns = records)
        val turn = fixture.runningTurn()
        val answer = assistant("durable answer", 1)
        fixture.connection.event(ended(answer))
        assertEquals(emptyMap(), records.get(fixture.session.ref)?.answerTurns)
        records.beforeWrite = { if (it.last != null) error("Disk unavailable") }
        assertFailsWith<EngineException> { fixture.connection.event(record("""{"type":"agent_settled"}""")) }
        assertEquals(emptyMap(), records.get(fixture.session.ref)?.answerTurns)
        assertEquals(turn, records.get(fixture.session.ref)?.active?.turn?.id)
        assertIs<ActiveSessionState.Unavailable>(fixture.session.state.value)
        records.beforeWrite = {}
        fixture.session.synchronize()
        val stored = records.get(fixture.session.ref)
        assertEquals(TurnOutcome.Completed, stored?.last?.turn?.outcome)
        assertEquals(turn, stored?.answerTurns?.get(piAnswerKey(answer)))
        fixture.session.shutdown()
    }

    private fun turn(id: String) = Turn(TurnId(id), prompt(id).id, RuntimeTarget)

    private fun assistant(text: String, timestamp: Int) = JsonObject(
        mapOf(
            "role" to JsonPrimitive("assistant"),
            "content" to JsonPrimitive(text),
            "timestamp" to JsonPrimitive(timestamp),
        ),
    )

    private fun ended(message: JsonObject) = JsonObject(
        mapOf("type" to JsonPrimitive("message_end"), "message" to message),
    )

    private companion object {
        val Route = ExecutionRoute(
            RuntimeRef.engine,
            RuntimeTarget.binding,
            RuntimeSource.info.id,
            RuntimeSource.info.revision,
            null,
        )
    }
}
