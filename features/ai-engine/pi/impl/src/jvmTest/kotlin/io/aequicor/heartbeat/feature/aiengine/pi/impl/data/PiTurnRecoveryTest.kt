@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiTurnRecoveryTest {
    @Test
    fun `authorization revoked during durable begin prevents native prompt delivery`() = runTest {
        val records = GatedPiTurnRecords()
        var isAllowed = true
        val fixture = fixture(this, turns = records, validate = {
            if (!isAllowed) piFailure(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        })
        records.beforeWrite = { if (it.active != null) isAllowed = false }
        assertFailsWith<EngineException> { fixture.session.send(prompt("request")) }
        assertFalse("prompt" in fixture.connection.commands)
        assertNull(records.get(fixture.session.ref)?.active)
        assertIs<TurnOutcome.Failed>(records.get(fixture.session.ref)?.last?.turn?.outcome)
        fixture.session.shutdown()
    }

    @Test
    fun `native prompt waits for durable request identity`() = runTest {
        val records = GatedPiTurnRecords()
        val fixture = fixture(this, turns = records)
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        val gate = CompletableDeferred<Unit>()
        records.beforeWrite = { if (it.active != null) gate.await() }
        val sending = async { fixture.session.send(prompt("request")) }
        runCurrent()
        assertIs<ActiveSessionState.Submitting>(fixture.session.state.value)
        assertFalse("prompt" in fixture.connection.commands)
        assertNull(records.get(fixture.session.ref))
        assertFailsWith<EngineException> { fixture.session.synchronize() }
        gate.complete(Unit)
        val turn = sending.await()
        assertEquals(turn, records.get(fixture.session.ref)?.active?.turn?.id)
        assertEquals(prompt("request").id, records.get(fixture.session.ref)?.active?.turn?.request)
        fixture.session.shutdown()
    }

    @Test
    fun `native settled waits for durable terminal receipt and failed write can retry`() = runTest {
        val records = GatedPiTurnRecords()
        val fixture = fixture(this, turns = records)
        val turn = fixture.runningTurn()
        records.beforeWrite = { if (it.last != null) error("Disk unavailable") }
        assertFailsWith<EngineException> { fixture.connection.event(record("""{"type":"agent_settled"}""")) }
        assertEquals(turn, records.get(fixture.session.ref)?.active?.turn?.id)
        assertIs<ActiveSessionState.Unavailable>(fixture.session.state.value)
        val gate = CompletableDeferred<Unit>()
        records.beforeWrite = { if (it.last != null) gate.await() }
        val finishing = async { fixture.session.synchronize() }
        runCurrent()
        assertFalse(finishing.isCompleted)
        assertIs<ActiveSessionState.Unavailable>(fixture.session.state.value)
        gate.complete(Unit)
        finishing.await()
        assertNull(records.get(fixture.session.ref)?.active)
        assertEquals(turn, assertIs<ActiveSessionState.Ready>(fixture.session.state.value).lastTurn?.id)
        assertEquals(TurnOutcome.Completed, records.get(fixture.session.ref)?.last?.turn?.outcome)
        fixture.session.shutdown()
    }

    @Test
    fun `fresh idle process cannot settle or resend the previous owned request`() = runTest {
        val records = MemoryPiTurnRecords()
        val first = fixture(this, turns = records)
        val turn = first.runningTurn()
        val ref = first.session.ref
        val second = fixture(this, transcript = PiTranscript(ref, "native.jsonl"), turns = records)
        assertEquals(turn, assertIs<ActiveSessionState.Unavailable>(second.session.state.value).activeTurn?.id)
        second.connection.event(record("""{"type":"agent_start"}"""))
        second.connection.event(record("""{"type":"agent_settled"}"""))
        second.session.synchronize()
        assertEquals(turn, assertIs<ActiveSessionState.Unavailable>(second.session.state.value).activeTurn?.id)
        assertFailsWith<EngineException> { second.session.send(prompt("new")) }
        assertFalse("prompt" in second.connection.commands)
        second.session.shutdown()
        assertEquals(turn, records.get(ref)?.active?.turn?.id)
        assertEquals(turn, assertIs<ActiveSessionState.Running>(first.session.state.value).turn.id)
        first.session.shutdown()
        assertEquals(TurnOutcome.Unknown, records.get(ref)?.last?.turn?.outcome)
    }

    @Test
    fun `known terminal request survives reopening with another selected model`() = runTest {
        val records = MemoryPiTurnRecords()
        val first = fixture(this, turns = records)
        val turn = first.runningTurn()
        first.connection.event(record("""{"type":"agent_settled"}"""))
        val saved = records.get(first.session.ref)?.last?.turn
        first.session.shutdown()
        val second = fixture(
            this,
            transcript = PiTranscript(first.session.ref, "native.jsonl"),
            targetModel = ModelId("anthropic/replacement"),
            turns = records,
        )
        assertEquals(saved, assertIs<ActiveSessionState.Ready>(second.session.state.value).lastTurn)
        assertEquals(turn, saved?.id)
        assertEquals(TurnOutcome.Completed, saved?.outcome)
        assertFalse("prompt" in second.connection.commands)
        second.session.shutdown()
    }

    @Test
    fun `owned route mismatch is rejected before starting a restored process`() = runTest {
        val records = MemoryPiTurnRecords()
        val first = runtimeFixture(this, records)
        val original = first.runtime.create(CreateSessionRequest(RuntimeTarget))
        first.processes.connections.single().promptAck.complete(JsonObject(emptyMap()))
        assertIs<FeatureAccess.Available<SendsPrompts>>(original.features.resolve(SendsPrompts))
            .feature.send(prompt("request"))
        val second = runtimeFixture(this, records)
        val other = RuntimeTarget.copy(binding = EngineBindingId("other"))
        second.settings.bind(other.binding, RuntimeSource)
        assertFailsWith<EngineException> { second.runtime.attach(original.ref, RuntimeRequest.copy(target = other)) }
        assertTrue(second.processes.connections.isEmpty())
        second.runtime.close()
        first.runtime.close()
    }

    @Test
    fun `shutdown stops every session when terminal persistence fails`() = runTest {
        val records = GatedPiTurnRecords()
        val fixture = runtimeFixture(this, records)
        fixture.processes.configure = {
            it.sessionId = "native-${fixture.processes.connections.size}"
            it.promptAck.complete(JsonObject(emptyMap()))
        }
        val sessions = List(2) {
            fixture.runtime.create(CreateSessionRequest(RuntimeTarget)).also { session ->
                assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts))
                    .feature.send(prompt("request-$it"))
            }
        }
        records.beforeWrite = { if (it.last != null) error("Disk unavailable") }
        fixture.runtime.close()
        sessions.forEach { session ->
            assertIs<ActiveSessionState.Closed>(session.state.value)
            assertTrue(records.get(session.ref)?.active != null)
        }
        assertTrue(fixture.processes.connections.all { it.isClosed && it.stopRequests > 0 })
    }
}

internal class GatedPiTurnRecords : PiTurnRecords {
    private val delegate = MemoryPiTurnRecords()
    var beforeWrite: suspend (PiTurnSnapshot) -> Unit = {}
    override suspend fun get(ref: SessionRef): PiTurnSnapshot? = delegate.get(ref)
    override suspend fun update(ref: SessionRef, transform: (PiTurnSnapshot?) -> PiTurnSnapshot): PiTurnSnapshot {
        beforeWrite(transform(delegate.get(ref)))
        return delegate.update(ref, transform)
    }
}
