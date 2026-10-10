@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PiTurnProcessOwnerTest {
    @Test
    fun `execution identity is durable before prompt and retained by the terminal receipt`() = runTest {
        val records = GatedPiTurnRecords()
        val fixture = fixture(this, turns = records)
        fixture.connection.owner = Owner
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        val gate = CompletableDeferred<Unit>()
        records.beforeWrite = { if (it.active != null) gate.await() }
        val sending = async { fixture.session.send(prompt("request")) }
        runCurrent()
        assertFalse("prompt" in fixture.connection.commands)
        assertNull(records.get(fixture.session.ref))
        gate.complete(Unit)
        sending.await()
        assertEquals(Owner, records.get(fixture.session.ref)?.active?.processOwner)
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        assertEquals(Owner, records.get(fixture.session.ref)?.last?.processOwner)
        assertEquals(TurnOutcome.Completed, records.get(fixture.session.ref)?.last?.turn?.outcome)
        fixture.session.shutdown()
    }

    @Test
    fun `process inspection finishes before beginning the durable request`() = runTest {
        val records = MemoryPiTurnRecords()
        val fixture = fixture(this, turns = records)
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        val capture = CompletableDeferred<PiExecutionOwner?>()
        fixture.connection.captureOwner = { capture.await() }
        val sending = async { fixture.session.send(prompt("request")) }
        runCurrent()
        assertNull(records.get(fixture.session.ref))
        assertFalse("prompt" in fixture.connection.commands)
        capture.complete(Owner)
        sending.await()
        assertEquals(Owner, records.get(fixture.session.ref)?.active?.processOwner)
        fixture.session.shutdown()
    }

    @Test
    fun `legacy record preserves absent stop evidence through serialization`() = runTest {
        val records = MemoryPiTurnRecords()
        val fixture = fixture(this, turns = records)
        fixture.runningTurn()
        val record = assertNotNull(records.get(fixture.session.ref)?.active)
        assertNull(record.processOwner)
        val raw = Json.encodeToString(record)
        assertFalse(raw.contains("processOwner"))
        assertNull(Json.decodeFromString<PiTurnRecord>(raw).processOwner)
        fixture.session.shutdown()
    }

    private companion object {
        val Owner = PiExecutionOwner("execution", PiProcessIdentity(123, "2026-10-06T00:00:00.123456789Z"))
    }
}
