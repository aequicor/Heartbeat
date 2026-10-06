package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PiStopOpeningTest {
    @Test
    fun `cold stop fence blocks launch through native completion until durable process exit`() = runTest {
        val records = MemoryPiTurnRecords()
        val first = runtimeFixture(this, records)
        first.processes.configure = {
            it.owner = Owner
            it.promptAck.complete(JsonObject(emptyMap()))
        }
        val session = first.runtime.create(CreateSessionRequest(RuntimeTarget))
        val turn = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts))
            .feature.send(prompt("request"))
        val journal = PiTurnJournal(records, session.ref, session.route, "fingerprint")
        val stop = assertNotNull(journal.fenceStop(prompt("request").id, turn))
        first.processes.connections.single().event(record("""{"type":"agent_settled"}"""))
        val second = runtimeFixture(this, records)
        val error = assertFailsWith<EngineException> { second.runtime.attach(session.ref, RuntimeRequest) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), error.failure)
        assertTrue(second.processes.connections.isEmpty())
        assertNotNull(journal.stopped(stop, Owner))
        val restored = second.runtime.attach(session.ref, RuntimeRequest)
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(restored.state.value).lastTurn?.outcome)
        second.runtime.close()
        first.runtime.close()
    }

    @Test
    fun `fence installed during process startup closes the candidate before transcript reattachment`() = runTest {
        val records = MemoryPiTurnRecords()
        val first = runtimeFixture(this, records)
        first.processes.configure = {
            it.owner = Owner
            it.promptAck.complete(JsonObject(emptyMap()))
        }
        val session = first.runtime.create(CreateSessionRequest(RuntimeTarget))
        val turn = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts))
            .feature.send(prompt("request"))
        val journal = PiTurnJournal(records, session.ref, session.route, "fingerprint")
        val second = runtimeFixture(this, records)
        second.processes.beforeStart = { assertNotNull(journal.fenceStop(prompt("request").id, turn)) }
        val error = assertFailsWith<EngineException> { second.runtime.attach(session.ref, RuntimeRequest) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), error.failure)
        val candidate = second.processes.connections.single()
        assertTrue(candidate.isClosed)
        assertTrue(candidate.commands.none { it == "switch_session" || it == "prompt" })
        assertEquals(turn, journal.restore()?.active?.turn?.id)
        assertNotNull(journal.restore()?.stopping)
        second.runtime.close()
        first.runtime.close()
    }

    @Test
    fun `fence installed during live recovery closes replacement before transcript reattachment`() = runTest {
        val records = MemoryPiTurnRecords()
        val fixture = runtimeFixture(this, records)
        fixture.processes.configure = {
            it.owner = Owner
            it.promptAck.complete(JsonObject(emptyMap()))
        }
        val session = fixture.runtime.create(CreateSessionRequest(RuntimeTarget))
        val turn = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts))
            .feature.send(prompt("request"))
        val journal = PiTurnJournal(records, session.ref, session.route, "fingerprint")
        val original = fixture.processes.connections.single()
        original.isOpen = false
        original.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        fixture.processes.beforeStart = { assertNotNull(journal.fenceStop(prompt("request").id, turn)) }
        val error = assertFailsWith<EngineException> {
            assertIs<FeatureAccess.Available<ReconcilesSession>>(session.features.resolve(ReconcilesSession))
                .feature.synchronize()
        }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), error.failure)
        assertEquals(2, fixture.processes.connections.size)
        val replacement = fixture.processes.connections.last()
        assertTrue(replacement.isClosed)
        assertTrue(replacement.commands.none { it == "switch_session" || it == "prompt" })
        assertEquals(turn, journal.restore()?.active?.turn?.id)
        assertNotNull(journal.restore()?.stopping)
        fixture.runtime.close()
    }

    private companion object {
        val Owner = PiExecutionOwner("execution", PiProcessIdentity(123, "2026-10-06T00:00:00Z"))
    }
}
