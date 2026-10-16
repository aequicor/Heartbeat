package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityState
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundAgentStartupTest {
    private val action = ActionId("background")
    private val request = RequestId("attempt")
    private val spawn = SpawnRequest(SESSION, PROJECT, TARGET, "Helper", WakePrompt(request, "Task", "Directive"))

    @Test
    fun `a cancelled first prompt proven not submitted releases ownership and capacity`() = runTest {
        val host = HelperHostFake().apply {
            beforePrompt = { throw CancellationException("Host submission cancelled") }
            revocation = { HelperCancellation.NotSubmitted(it) }
        }
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        assertNull(fixture.actions.startAgent(action, spawn, retainedBy = "owner", awaitAcceptance = false))
        runCurrent()
        assertEquals(listOf(request), host.revocations)
        assertTrue(host.cancellations.isEmpty())
        assertFalse(fixture.results.isOwned(action))
        assertTrue((fixture.capacityMachine.state.value as BackgroundCapacityState.Ready).active.isEmpty())
        assertTrue(fixture.journal.records.single().payload.orEmpty().contains("not submitted"))
    }

    @Test
    fun `a cancelled first prompt with uncertain acceptance keeps the exact attempt supervised`() = runTest {
        val host = HelperHostFake().apply {
            beforePrompt = { throw CancellationException("Host submission cancelled") }
            revocation = { HelperCancellation.Unconfirmed(it) }
        }
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        assertNull(fixture.actions.startAgent(action, spawn, retainedBy = "owner", awaitAcceptance = false))
        runCurrent()
        assertEquals(listOf(request), host.revocations)
        assertTrue(fixture.results.isOwned(action))
        assertNull(fixture.journal.records.single().payload)
        assertEquals(1, (fixture.capacityMachine.state.value as BackgroundCapacityState.Ready).active.size)
        host.results[request] = HelperResult(request, HelperOutcome.Completed, "accepted after all")
        fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
        runCurrent()
        assertTrue(host.cancellations.isEmpty())
        assertFalse(fixture.results.isOwned(action))
        assertTrue((fixture.capacityMachine.state.value as BackgroundCapacityState.Ready).active.isEmpty())
        assertTrue(fixture.journal.records.single().payload.orEmpty().contains("accepted after all"))
    }

    @Test
    fun `profile cancellation during the first prompt leaves a durable attempt for recovery`() = runTest {
        val job = SupervisorJob(backgroundScope.coroutineContext[Job])
        val profile = TestScopeHandle(CoroutineScope(backgroundScope.coroutineContext + job))
        val host = HelperHostFake().apply { beforePrompt = { awaitCancellation() } }
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host), profile = profile)
        assertNull(fixture.actions.startAgent(action, spawn, retainedBy = "owner", awaitAcceptance = false))
        runCurrent()
        assertEquals(listOf(request), host.prompts.map { it.request })
        job.cancelAndJoin()
        assertFalse(fixture.results.isOwned(action))
        assertNull(fixture.journal.records.single().payload)
        assertTrue(host.revocations.isEmpty())
        assertTrue(host.cancellations.isEmpty())
    }

    @Test
    fun `caller cancellation does not cancel the profile owned startup`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = HelperHostFake().apply { beforeCreate = { gate.await() } }
        val fixture = ActionsFixture(this, SpecMachine(), hosts = setOf(host))
        val caller = async {
            fixture.actions.startAgent(action, spawn, retainedBy = "owner", awaitAcceptance = false)
        }
        runCurrent()
        caller.cancelAndJoin()
        assertTrue(caller.isCancelled)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(request), host.prompts.map { it.request })
        host.results[request] = HelperResult(request, HelperOutcome.Completed, "done")
        fixture.bus.publish(EventKeys.turnFinished(OTHER), EventOrigin.Host)
        runCurrent()
        assertFalse(fixture.results.isOwned(action))
        assertTrue(fixture.journal.records.single().payload.orEmpty().contains("done"))
    }
}
