@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiOwnedTurnsTest {
    @Test
    fun `live stop persists exact receipt and permits a distinct request on recovered process`() = runTest {
        val fixture = runtimeFixture(this)
        fixture.processes.configure = {
            it.owner = Owner
            it.promptAck.complete(JsonObject(emptyMap()))
        }
        val session = fixture.runtime.create(CreateSessionRequest(RuntimeTarget)) as PiSession
        val turn = session.send(prompt("first"))
        val result = assertIs<OwnedTurnStop.Confirmed>(fixture.stop(session, "first", turn))
        assertEquals(TurnOutcome.Unknown, result.turn.outcome)
        assertTrue(fixture.processes.connections.first().isClosed)
        assertIs<ActiveSessionState.Ready>(session.state.value)
        val next = session.send(prompt("second"))
        assertTrue(next != turn)
        assertEquals(2, fixture.processes.connections.size)
        fixture.runtime.close()
    }

    @Test
    fun `stop during durable preparation prevents every delayed prompt`() = runTest {
        val records = GatedPiTurnRecords()
        val fixture = runtimeFixture(this, records)
        val session = fixture.runtime.create(CreateSessionRequest(RuntimeTarget)) as PiSession
        val gate = CompletableDeferred<Unit>()
        records.beforeWrite = { if (it.active != null) gate.await() }
        val sending = async { assertFailsWith<EngineException> { session.send(prompt("pending")) } }
        runCurrent()
        val stopping = async { fixture.stop(session, "pending") }
        runCurrent()
        assertFalse(stopping.isCompleted)
        sending.await()
        gate.complete(Unit)
        val result = assertIs<OwnedTurnStop.Confirmed>(stopping.await())
        assertEquals(TurnOutcome.Cancelled, result.turn.outcome)
        assertFalse("prompt" in fixture.processes.connections.single().commands)
        assertEquals(0, fixture.processes.stopCalls)
        assertNull(records.get(session.ref)?.active)
        fixture.runtime.close()
    }

    @Test
    fun `failed process stop retains admission and exact fence for retry`() = runTest {
        val records = MemoryPiTurnRecords()
        val fixture = runtimeFixture(this, records)
        fixture.processes.configure = {
            it.owner = Owner
            it.promptAck.complete(JsonObject(emptyMap()))
        }
        val session = fixture.runtime.create(CreateSessionRequest(RuntimeTarget)) as PiSession
        val turn = session.send(prompt("request"))
        fixture.processes.stopResult = false
        assertEquals(OwnedTurnStop.Unconfirmed, fixture.stop(session, "request", turn))
        assertEquals(turn, records.get(session.ref)?.stopping?.turn?.id)
        assertFailsWith<EngineException> { session.send(prompt("later")) }
        fixture.processes.connections.single().event(record("""{"type":"agent_settled"}"""))
        assertEquals(turn, records.get(session.ref)?.active?.turn?.id)
        fixture.processes.stopResult = true
        assertIs<OwnedTurnStop.Confirmed>(fixture.stop(session, "request", turn))
        fixture.runtime.close()
    }

    @Test
    fun `cold stop never starts or reads a transcript and a wrong route does not poison retry`() = runTest {
        val records = MemoryPiTurnRecords()
        val first = runtimeFixture(this, records)
        first.processes.configure = {
            it.owner = Owner
            it.promptAck.complete(JsonObject(emptyMap()))
        }
        val session = first.runtime.create(CreateSessionRequest(RuntimeTarget)) as PiSession
        val turn = session.send(prompt("request"))
        val second = runtimeFixture(this, records)
        assertFailsWith<EngineException> {
            second.stopper().stop(
                session.ref,
                prompt("request").id,
                OwnedTurnAccess(RuntimeTarget, WorkspaceRef("foreign")),
            )
        }
        assertIs<OwnedTurnStop.Confirmed>(second.stop(session, "request", turn))
        assertTrue(second.processes.connections.isEmpty())
        assertEquals(0, second.processes.transcriptReads)
        assertEquals(1, second.processes.stopCalls)
        first.runtime.close()
        second.runtime.close()
    }

    @Test
    fun `historical request cannot kill a newer live turn and reused request selects active turn`() = runTest {
        val fixture = runtimeFixture(this)
        fixture.processes.configure = {
            it.owner = Owner
            it.promptAck.complete(JsonObject(emptyMap()))
        }
        val session = fixture.runtime.create(CreateSessionRequest(RuntimeTarget)) as PiSession
        val old = session.send(prompt("old"))
        fixture.processes.connections.single().event(record("""{"type":"agent_settled"}"""))
        val next = session.send(prompt("new"))
        assertEquals(old, assertIs<OwnedTurnStop.Confirmed>(fixture.stop(session, "old")).turn.id)
        assertEquals(0, fixture.processes.stopCalls)
        assertEquals(next, assertIs<ActiveSessionState.Running>(session.state.value).turn.id)
        assertEquals(OwnedTurnStop.Unconfirmed, fixture.stop(session, "new", TurnId("foreign")))
        assertIs<OwnedTurnStop.Confirmed>(fixture.stop(session, "new", next))
        val reused = session.send(prompt("new"))
        assertEquals(reused, assertIs<OwnedTurnStop.Confirmed>(fixture.stop(session, "new")).turn.id)
        fixture.runtime.close()
    }

    @Test
    fun `hosted noncancellable cleanup blocks stop without holding runtime administration`() = runTest {
        val fixture = runtimeFixture(this)
        fixture.processes.configure = {
            it.owner = Owner
            it.promptAck.complete(JsonObject(emptyMap()))
        }
        val session = fixture.runtime.create(CreateSessionRequest(RuntimeTarget)) as PiSession
        val turn = session.send(prompt("request"))
        val gate = CompletableDeferred<Unit>()
        backgroundScope.launch(backgroundScope.coroutineContext + checkNotNull(session.hostedJobs.lifetime(turn))) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { gate.await() }
            }
        }
        runCurrent()
        val stopping = async { fixture.stop(session, "request", turn) }
        runCurrent()
        assertFalse(stopping.isCompleted)
        assertEquals(0, fixture.processes.stopCalls)
        fixture.processes.configure = { it.sessionId = "unrelated" }
        val unrelated = fixture.runtime.create(CreateSessionRequest(RuntimeTarget))
        assertEquals("unrelated", unrelated.ref.nativeId)
        gate.complete(Unit)
        assertIs<OwnedTurnStop.Confirmed>(stopping.await())
        fixture.runtime.close()
    }

    @Test
    fun `caller cancellation during preflight preserves exact stop admission`() = runTest {
        val fixture = runtimeFixture(this)
        val session = fixture.runtime.create(CreateSessionRequest(RuntimeTarget)) as PiSession
        val gate = CompletableDeferred<Unit>()
        fixture.processes.connections.single().captureOwner = {
            gate.await()
            Owner
        }
        val sending = async { session.send(prompt("pending")) }
        runCurrent()
        sending.cancelAndJoin()
        assertFailsWith<EngineException> { session.send(prompt("other")) }
        val stopping = async { fixture.stop(session, "pending") }
        runCurrent()
        gate.complete(Unit)
        assertIs<OwnedTurnStop.Confirmed>(stopping.await())
        assertFalse("prompt" in fixture.processes.connections.single().commands)
        fixture.runtime.close()
    }

    @Test
    fun `cancelled stop waiter retains its revocation and can be retried`() = runTest {
        val fixture = runtimeFixture(this)
        fixture.processes.configure = {
            it.owner = Owner
            it.promptAck.complete(JsonObject(emptyMap()))
        }
        val session = fixture.runtime.create(CreateSessionRequest(RuntimeTarget)) as PiSession
        val turn = session.send(prompt("request"))
        fixture.processes.beforeStop = { awaitCancellation() }
        val stopping = async { fixture.stop(session, "request", turn) }
        runCurrent()
        stopping.cancelAndJoin()
        assertFailsWith<EngineException> { session.send(prompt("later")) }
        fixture.processes.beforeStop = {}
        assertIs<OwnedTurnStop.Confirmed>(fixture.stop(session, "request", turn))
        fixture.runtime.close()
    }

    @Test
    fun `legacy active turn without process identity is never falsely confirmed`() = runTest {
        val fixture = runtimeFixture(this)
        fixture.processes.configure = { it.promptAck.complete(JsonObject(emptyMap())) }
        val session = fixture.runtime.create(CreateSessionRequest(RuntimeTarget)) as PiSession
        val turn = session.send(prompt("request"))
        assertEquals(OwnedTurnStop.Unconfirmed, fixture.stop(session, "request", turn))
        assertEquals(0, fixture.processes.stopCalls)
        assertFailsWith<EngineException> { session.send(prompt("later")) }
        fixture.runtime.close()
    }

    @Test
    fun `failed durable stop receipt keeps live claim until storage recovers`() = runTest {
        val records = GatedPiTurnRecords()
        val fixture = runtimeFixture(this, records)
        fixture.processes.configure = {
            it.owner = Owner
            it.promptAck.complete(JsonObject(emptyMap()))
        }
        val session = fixture.runtime.create(CreateSessionRequest(RuntimeTarget)) as PiSession
        val turn = session.send(prompt("request"))
        records.beforeWrite = { if (it.last?.isProcessStopped == true) error("Disk unavailable") }
        assertFailsWith<EngineException> { fixture.stop(session, "request", turn) }
        assertFailsWith<EngineException> { session.send(prompt("later")) }
        records.beforeWrite = {}
        assertIs<OwnedTurnStop.Confirmed>(fixture.stop(session, "request", turn))
        fixture.runtime.close()
    }

    @Test
    fun `retained hosted cleanup survives runtime replacement before cold stop`() = runTest {
        val records = MemoryPiTurnRecords()
        val drains = PiHostedDrains()
        val first = runtimeFixture(this, records, drains)
        first.processes.configure = {
            it.owner = Owner
            it.promptAck.complete(JsonObject(emptyMap()))
        }
        val session = first.runtime.create(CreateSessionRequest(RuntimeTarget)) as PiSession
        val turn = session.send(prompt("request"))
        val gate = CompletableDeferred<Unit>()
        backgroundScope.launch(backgroundScope.coroutineContext + checkNotNull(session.hostedJobs.lifetime(turn))) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { gate.await() }
            }
        }
        runCurrent()
        first.processes.connections.single().stopProof = { false }
        first.runtime.close()
        val second = runtimeFixture(this, records, drains)
        assertEquals(OwnedTurnStop.Unconfirmed, second.stop(session, "request", turn))
        assertEquals(0, second.processes.stopCalls)
        gate.complete(Unit)
        runCurrent()
        assertIs<OwnedTurnStop.Confirmed>(second.stop(session, "request", turn))
        assertTrue(second.processes.connections.isEmpty())
        second.runtime.close()
    }

    @Test
    fun `late acknowledgement after stop cannot settle or clear admission of a new request`() = runTest {
        val fixture = runtimeFixture(this)
        fixture.processes.configure = { it.owner = Owner }
        val session = fixture.runtime.create(CreateSessionRequest(RuntimeTarget)) as PiSession
        val oldProcess = fixture.processes.connections.single()
        val old = async { assertFailsWith<EngineException> { session.send(prompt("old")) } }
        runCurrent()
        assertIs<OwnedTurnStop.Confirmed>(fixture.stop(session, "old"))
        old.await()
        val next = async { session.send(prompt("new")) }
        runCurrent()
        val nextState = assertIs<ActiveSessionState.Submitting>(session.state.value)
        oldProcess.promptAck.complete(JsonObject(emptyMap()))
        runCurrent()
        assertFalse(next.isCompleted)
        assertEquals(nextState.turn.id, assertIs<ActiveSessionState.Submitting>(session.state.value).turn.id)
        assertFailsWith<EngineException> { session.synchronize() }
        fixture.processes.connections.last().promptAck.complete(JsonObject(emptyMap()))
        assertEquals(nextState.turn.id, next.await())
        fixture.runtime.close()
    }

    private fun RuntimeFixture.stopper() = assertIs<FeatureAccess.Available<StopsOwnedTurns>>(
        runtime.features.resolve(StopsOwnedTurns),
    ).feature

    private suspend fun RuntimeFixture.stop(session: PiSession, request: String, turn: TurnId? = null) =
        stopper().stop(session.ref, prompt(request).id, OwnedTurnAccess(RuntimeTarget, expectedTurn = turn))

    private companion object {
        val Owner = PiExecutionOwner("launch", PiProcessIdentity(123L, "2026-01-01T00:00:00Z"), emptyList())
    }
}
