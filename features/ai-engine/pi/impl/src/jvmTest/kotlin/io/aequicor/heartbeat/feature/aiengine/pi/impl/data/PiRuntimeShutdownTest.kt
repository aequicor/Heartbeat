package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiRuntimeShutdownTest {
    @Test
    fun `runtime shutdown stops an accepted turn and preserves its history and receipt`() = runTest {
        val records = MemoryPiTurnRecords()
        val fixture = runtimeFixture(this, records)
        val session = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        val connection = fixture.processes.connections.single()
        connection.promptAck.complete(JsonObject(emptyMap()))
        val prompts = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts)).feature
        val history = assertIs<FeatureAccess.Available<SessionHistory>>(
            session.features.resolve(SessionHistory),
        ).feature
        val turn = prompts.send(prompt("disconnect"))
        val before = history.page(HistoryPageRequest()).items
        assertTrue(before.isNotEmpty())
        assertIs<ActiveSessionState.Running>(session.state.value)

        fixture.runtime.close()

        assertEquals(1, connection.stopRequests)
        assertTrue(connection.isClosed)
        assertIs<ActiveSessionState.Closed>(session.state.value)
        val saved = assertNotNull(records.get(session.ref))
        assertNull(saved.active)
        assertEquals(turn, saved.last?.turn?.id)
        assertEquals(prompt("disconnect").id, saved.last?.turn?.request)
        assertEquals(TurnOutcome.Unknown, saved.last?.turn?.outcome)
        assertEquals(before, history.page(HistoryPageRequest()).items)
    }

    @Test
    fun `runtime shutdown stops an accepted turn after its last handle is released`() = runTest {
        val records = MemoryPiTurnRecords()
        val fixture = runtimeFixture(this, records)
        val session = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        val connection = fixture.processes.connections.single()
        connection.promptAck.complete(JsonObject(emptyMap()))
        val prompts = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts)).feature
        val turn = prompts.send(prompt("detached-disconnect"))
        session.close()
        assertFalse(connection.isClosed)
        assertEquals(0, connection.stopRequests)
        assertEquals(turn, records.get(session.ref)?.active?.turn?.id)

        fixture.runtime.close()

        assertEquals(1, connection.stopRequests)
        assertTrue(connection.isClosed)
        val saved = assertNotNull(records.get(session.ref))
        assertNull(saved.active)
        assertEquals(turn, saved.last?.turn?.id)
        assertEquals(TurnOutcome.Unknown, saved.last?.turn?.outcome)
        session.close()
        fixture.runtime.close()
        assertEquals(1, connection.stopRequests)
    }

    @Test
    fun `unconfirmed shutdown keeps the process and active receipt for retry after handle closure`() = runTest {
        for (isDetached in listOf(false, true)) {
            val records = MemoryPiTurnRecords()
            val fixture = runtimeFixture(this, records)
            val session = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
            val connection = fixture.processes.connections.single()
            connection.promptAck.complete(JsonObject(emptyMap()))
            val prompts = assertIs<FeatureAccess.Available<SendsPrompts>>(
                session.features.resolve(SendsPrompts),
            ).feature
            val history = assertIs<FeatureAccess.Available<SessionHistory>>(
                session.features.resolve(SessionHistory),
            ).feature
            val turn = prompts.send(prompt("retry-disconnect"))
            val before = history.page(HistoryPageRequest()).items
            if (isDetached) session.close()
            connection.stopProof = { false }

            assertFailsWith<EngineException> { fixture.runtime.close() }
            assertEquals(1, connection.stopRequests)
            assertEquals(turn, records.get(session.ref)?.active?.turn?.id)
            assertNull(records.get(session.ref)?.last)
            assertIs<ActiveSessionState.Closed>(session.state.value)
            assertEquals(before, history.page(HistoryPageRequest()).items)

            // The facade releases every handle even when runtime shutdown failed.
            session.close()
            connection.stopProof = { true }
            fixture.runtime.close()

            assertEquals(2, connection.stopRequests)
            val stopped = assertNotNull(records.get(session.ref))
            assertNull(stopped.active)
            assertEquals(turn, stopped.last?.turn?.id)
            assertEquals(TurnOutcome.Unknown, stopped.last?.turn?.outcome)
            assertEquals(before, history.page(HistoryPageRequest()).items)
            fixture.runtime.close()
            assertEquals(2, connection.stopRequests)
        }
    }

    @Test
    fun `failed shutdown still stops other sessions and retries only the failed process`() = runTest {
        val records = MemoryPiTurnRecords()
        val fixture = runtimeFixture(this, records)
        val sessions = (1..2).map { index ->
            val nativeId = "native-$index"
            fixture.processes.configure = { it.sessionId = nativeId }
            val session = fixture.runtime.attach(RuntimeRef.copy(nativeId = nativeId), RuntimeRequest)
            val connection = fixture.processes.connections.last()
            connection.promptAck.complete(JsonObject(emptyMap()))
            val prompts = assertIs<FeatureAccess.Available<SendsPrompts>>(
                session.features.resolve(SendsPrompts),
            ).feature
            prompts.send(prompt("disconnect-$index"))
            session
        }
        val failed = fixture.processes.connections.first()
        val stopped = fixture.processes.connections.last()
        failed.stopProof = { throw IllegalStateException("Process exit observation failed") }

        assertFailsWith<IllegalStateException> { fixture.runtime.close() }

        assertEquals(1, failed.stopRequests)
        assertEquals(1, stopped.stopRequests)
        assertNotNull(records.get(sessions.first().ref)?.active)
        assertNull(records.get(sessions.last().ref)?.active)
        assertEquals(TurnOutcome.Unknown, records.get(sessions.last().ref)?.last?.turn?.outcome)
        sessions.forEach { it.close() }
        failed.stopProof = { true }

        fixture.runtime.close()

        assertEquals(2, failed.stopRequests)
        assertEquals(1, stopped.stopRequests)
        assertNull(records.get(sessions.first().ref)?.active)
        assertEquals(TurnOutcome.Unknown, records.get(sessions.first().ref)?.last?.turn?.outcome)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `concurrent runtime closes share the same process shutdown`() = runTest {
        val fixture = runtimeFixture(this)
        fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        val connection = fixture.processes.connections.single()
        val stopped = CompletableDeferred<Boolean>()
        connection.stopProof = { stopped.await() }

        val first = async { fixture.runtime.close() }
        runCurrent()
        val second = async { fixture.runtime.close() }
        runCurrent()
        assertEquals(1, connection.stopRequests)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        stopped.complete(true)
        first.await()
        second.await()
        assertEquals(1, connection.stopRequests)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `shutdown waits for late startup and retains an unconfirmed late process for retry`() = runTest {
        val fixture = runtimeFixture(this)
        val startup = CompletableDeferred<Unit>()
        fixture.processes.beforeStart = { startup.await() }
        fixture.processes.configure = { it.stopProof = { false } }
        val opening = async {
            assertFailsWith<EngineException> { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        }
        runCurrent()
        val shutdown = async { assertFailsWith<EngineException> { fixture.runtime.close() } }
        runCurrent()
        assertFalse(shutdown.isCompleted)

        startup.complete(Unit)
        opening.await()
        shutdown.await()
        val connection = fixture.processes.connections.single()
        val attempts = connection.stopRequests
        assertTrue(attempts > 0)
        connection.stopProof = { true }

        fixture.runtime.close()

        assertEquals(attempts + 1, connection.stopRequests)
        fixture.runtime.close()
        assertEquals(attempts + 1, connection.stopRequests)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `shutdown stops accepted work before an unrelated session finishes starting`() = runTest {
        val fixture = runtimeFixture(this)
        val session = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        val active = fixture.processes.connections.single()
        active.promptAck.complete(JsonObject(emptyMap()))
        assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts))
            .feature.send(prompt("active"))
        val startup = CompletableDeferred<Unit>()
        fixture.processes.beforeStart = { startup.await() }
        fixture.processes.configure = { it.sessionId = "late" }
        val opening = async {
            assertFailsWith<EngineException> {
                fixture.runtime.attach(RuntimeRef.copy(nativeId = "late"), RuntimeRequest)
            }
        }
        runCurrent()

        val shutdown = async { fixture.runtime.close() }
        runCurrent()

        assertEquals(1, active.stopRequests)
        assertIs<ActiveSessionState.Closed>(session.state.value)
        assertFalse(shutdown.isCompleted)
        startup.complete(Unit)
        opening.await()
        shutdown.await()
        assertEquals(1, fixture.processes.connections.last().stopRequests)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `shutdown owns a late recovery process until its exit is confirmed`() = runTest {
        val fixture = runtimeFixture(this)
        val session = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        val original = fixture.processes.connections.single()
        original.isOpen = false
        original.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        val startup = CompletableDeferred<Unit>()
        fixture.processes.beforeStart = { startup.await() }
        fixture.processes.configure = { it.stopProof = { false } }
        val recovery = async {
            assertFailsWith<EngineException> {
                assertIs<FeatureAccess.Available<ReconcilesSession>>(session.features.resolve(ReconcilesSession))
                    .feature.synchronize()
            }
        }
        runCurrent()
        val shutdown = async { assertFailsWith<EngineException> { fixture.runtime.close() } }
        runCurrent()
        assertFalse(shutdown.isCompleted)
        startup.complete(Unit)
        recovery.await()
        shutdown.await()

        val replacement = fixture.processes.connections.last()
        assertEquals(2, fixture.processes.connections.size)
        assertEquals(1, replacement.stopRequests)
        session.close()
        replacement.stopProof = { true }
        fixture.runtime.close()
        assertEquals(2, replacement.stopRequests)
    }
}
