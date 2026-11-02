package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceTurnRecoveryTest {
    @Test
    fun `cold recovery stops the original request without create or replay`() = runTest {
        val f = IsolatedTurnFixture()
        val work = ServiceTurn(f.target, RequestId("old-r"), "source", 5.seconds, 100)
        val attempt = f.journal.begin(work, listOf("shell"))
        f.journal.opened(attempt.binding, f.ref)
        f.journal.prepare(attempt.binding)
        f.journal.enterPrompt(attempt.binding, f.ref, work.request)
        var calls = 0
        f.ownedStops = object : StopsOwnedTurns {
            override suspend fun stop(ref: SessionRef, request: RequestId, access: OwnedTurnAccess): OwnedTurnStop {
                assertEquals(f.ref, ref)
                assertEquals(work.request, request)
                assertEquals(work.target, access.target)
                calls++
                return OwnedTurnStop.Confirmed(Turn(TurnId("native-r"), request, access.target, TurnOutcome.Unknown))
            }
        }
        val restored = ServiceTurnJournal(f.serviceStore)
        ServiceTurnHost(lazyOf(f.facade), AgentSafetyToolPolicy(restored), restored, f.control).recover()
        assertEquals(1, calls)
        assertEquals(ServiceStage.Settled, restored.get(attempt.binding).stage)
        assertTrue(restored.get(attempt.binding).isRetired)
        assertTrue(f.creations.isEmpty())
        assertTrue(f.prompts.isEmpty())
    }

    @Test
    fun `unacknowledged creation remains quarantined on restart`() = runTest {
        val f = IsolatedTurnFixture()
        val attempt = f.journal.begin(
            ServiceTurn(f.target, RequestId("opening"), "source", 5.seconds, 100),
            emptyList(),
        )
        val restored = ServiceTurnJournal(f.serviceStore)
        ServiceTurnHost(lazyOf(f.facade), AgentSafetyToolPolicy(restored), restored, f.control).recover()
        assertEquals(ServiceStage.Opening, restored.get(attempt.binding).stage)
        assertFalse(restored.permits(attempt.binding))
        assertTrue(f.creations.isEmpty())
    }

    @Test
    fun `timeout stops waiting without cancelling the tracked send handoff`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = IsolatedTurnFixture().apply { sendGate = gate }
        val prompts = FacadeIsolatedPrompts(
            f.host,
            backgroundScope,
            IsolatedTurnSlots(1, queueWait = 1.seconds),
            1.minutes,
        )
        val result = async { prompts.ask(IsolatedPrompt(f.target, "source", timeout = 1.seconds)) }
        runCurrent()
        advanceTimeBy(12.seconds)
        runCurrent()
        assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout), result.await())
        assertFalse(gate.isCancelled)
        assertFalse(f.closed)
        assertEquals(ServiceStage.Sending, f.journal.attempts().single().stage)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, f.prompts.size)
        assertEquals(ServiceStage.Settled, f.journal.attempts().single().stage)
        assertTrue(f.closed)
    }
}
