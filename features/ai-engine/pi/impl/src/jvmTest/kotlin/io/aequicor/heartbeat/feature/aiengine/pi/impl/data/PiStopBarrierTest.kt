@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PiStopBarrierTest {
    @Test
    fun `closed transport cannot create a recovery process before confirmed exit`() = runTest {
        val fixture = fixture(this)
        val turn = fixture.runningTurn()
        val exited = CompletableDeferred<Boolean>()
        fixture.connection.stopProof = { exited.await() }
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        val recovery = async { fixture.session.synchronize() }
        runCurrent()
        assertFalse(recovery.isCompleted)
        assertEquals(1, fixture.connections.size)
        assertEquals(turn, assertIs<ActiveSessionState.Unavailable>(fixture.session.state.value).activeTurn?.id)
        exited.complete(true)
        recovery.await()
        assertEquals(2, fixture.connections.size)
        val settled = assertIs<ActiveSessionState.Ready>(fixture.session.state.value).lastTurn
        assertEquals(turn, settled?.id)
        assertEquals(TurnOutcome.Unknown, settled?.outcome)
        fixture.session.shutdown()
    }

    @Test
    fun `unconfirmed exit retains original connection for a later recovery attempt`() = runTest {
        val fixture = fixture(this)
        val turn = fixture.runningTurn()
        fixture.connection.stopProof = { false }
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        assertFailsWith<EngineException> { fixture.session.synchronize() }
        assertEquals(1, fixture.connections.size)
        assertEquals(turn, assertIs<ActiveSessionState.Unavailable>(fixture.session.state.value).activeTurn?.id)
        fixture.connection.stopProof = { true }
        fixture.session.synchronize()
        assertEquals(2, fixture.connection.stopRequests)
        assertEquals(2, fixture.connections.size)
        fixture.session.shutdown()
    }

    @Test
    fun `cancelled exit waiter retains the request and never starts a second process`() = runTest {
        val fixture = fixture(this)
        val turn = fixture.runningTurn()
        val exited = CompletableDeferred<Boolean>()
        fixture.connection.stopProof = { exited.await() }
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        val recovery = async { fixture.session.synchronize() }
        runCurrent()
        recovery.cancelAndJoin()
        assertEquals(1, fixture.connections.size)
        assertEquals(turn, assertIs<ActiveSessionState.Unavailable>(fixture.session.state.value).activeTurn?.id)
        exited.complete(true)
        fixture.session.synchronize()
        assertEquals(2, fixture.connections.size)
        fixture.session.shutdown()
    }

    @Test
    fun `shutdown cannot publish unknown while its process is still stopping`() = runTest {
        val fixture = fixture(this)
        val turn = fixture.runningTurn()
        val exited = CompletableDeferred<Boolean>()
        fixture.connection.stopProof = { exited.await() }
        val shutdown = async { fixture.session.shutdown() }
        runCurrent()
        assertFalse(shutdown.isCompleted)
        assertEquals(turn, assertIs<ActiveSessionState.Running>(fixture.session.state.value).turn.id)
        exited.complete(true)
        shutdown.await()
        assertIs<ActiveSessionState.Closed>(fixture.session.state.value)
        assertEquals(listOf(fixture.session), fixture.released)
    }

    @Test
    fun `recovery waiting for exit cannot reopen a session after shutdown`() = runTest {
        val fixture = fixture(this)
        fixture.runningTurn()
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        val recoveryExit = CompletableDeferred<Boolean>()
        var waits = 0
        fixture.connection.stopProof = { if (++waits == 1) recoveryExit.await() else true }
        val recovery = async { assertFailsWith<EngineException> { fixture.session.synchronize() } }
        runCurrent()
        fixture.session.shutdown()
        recoveryExit.complete(true)
        recovery.await()
        assertEquals(1, fixture.connections.size)
        assertIs<ActiveSessionState.Closed>(fixture.session.state.value)
        assertEquals(listOf(fixture.session), fixture.released)
    }

    @Test
    fun `shutdown during native restore discards the fresh process`() = runTest {
        val restored = CompletableDeferred<Unit>()
        val fixture = fixture(this) { index, connection ->
            if (index == 1) connection.onSetModel = { restored.await() }
        }
        fixture.runningTurn()
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        val recovery = async { assertFailsWith<EngineException> { fixture.session.synchronize() } }
        runCurrent()
        assertEquals(2, fixture.connections.size)
        fixture.session.shutdown()
        restored.complete(Unit)
        recovery.await()
        assertEquals(2, fixture.connections.size)
        assertEquals(true, fixture.connections.last().isClosed)
        assertIs<ActiveSessionState.Closed>(fixture.session.state.value)
    }
}
