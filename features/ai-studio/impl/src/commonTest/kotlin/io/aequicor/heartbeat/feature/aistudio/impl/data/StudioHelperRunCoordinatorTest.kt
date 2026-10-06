package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudioHelperRunCoordinatorTest {
    @Test
    fun `caller supplied durable gate cannot be replaced by scheduled preparation`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val journal = StudioHelperAttempts(ChecklistTestStores())
        val helper = HelperId("helper")
        val request = runRequest("revoked")
        journal.prepare(helper, HelperPrompt(request.request, request.prompt))
        journal.cancelBeforeSubmission(helper, request.request)
        val gate = StudioHelperSubmission(journal, helper, request.request)
        host.execute = { actual ->
            assertTrue(actual.submission === gate)
            actual.submission?.begin()
            events += "submitted"
            RunOutcome.Completed
        }
        assertFailsWith<CancellationException> {
            coordinator.run(host, request.copy(submission = gate), cancelBeforeSubmission = true)
        }
        assertFalse("submitted" in events)
        assertTrue("finished" in events)
        host.execute = { RunOutcome.Completed }
        assertEquals(RunOutcome.Completed, coordinator.run(host, runRequest("next")))
    }

    @Test
    fun `durable revocation interrupts pending preparation without waiting for native send`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val journal = StudioHelperAttempts(ChecklistTestStores())
        val helper = HelperId("helper")
        val request = runRequest("pending")
        journal.prepare(helper, HelperPrompt(request.request, request.prompt))
        val gate = StudioHelperSubmission(journal, helper, request.request)
        val preparing = CompletableDeferred<Unit>()
        host.execute = { actual ->
            preparing.await()
            actual.submission?.begin()
            events += "submitted"
            RunOutcome.Completed
        }
        val run = async { coordinator.run(host, request.copy(submission = gate)) }
        runCurrent()
        journal.cancelBeforeSubmission(helper, request.request)
        runCurrent()
        assertFailsWith<CancellationException> { run.await() }
        assertFalse(preparing.isCompleted)
        assertFalse("submitted" in events)
        assertTrue("finished" in events)
    }

    @Test
    fun `caller cancellation persists durable revocation despite cancelled waiter context`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val stores = AttemptStores()
        val journal = StudioHelperAttempts(stores)
        val helper = HelperId("helper")
        val request = runRequest("pending")
        journal.prepare(helper, HelperPrompt(request.request, request.prompt))
        val gate = StudioHelperSubmission(journal, helper, request.request)
        val preparing = CompletableDeferred<Unit>()
        host.execute = { actual ->
            preparing.await()
            actual.submission?.begin()
            events += "submitted"
            RunOutcome.Completed
        }
        val run = async { coordinator.run(host, request.copy(submission = gate)) }
        runCurrent()
        val write = CompletableDeferred<Unit>()
        stores.beforeWrite = {
            currentCoroutineContext().ensureActive()
            write.await()
        }
        run.cancel()
        runCurrent()
        assertFalse(run.isCompleted)
        write.complete(Unit)
        run.join()
        runCurrent()
        assertEquals(StudioHelperPhase.NotSubmitted, journal.receipt(helper, request.request)?.phase)
        assertTrue("finished" in events)
        preparing.complete(Unit)
        runCurrent()
        assertFalse("submitted" in events)
        host.execute = { RunOutcome.Completed }
        assertEquals(RunOutcome.Completed, coordinator.run(host, runRequest("next")))
    }

    @Test
    fun `failed cancellation persistence still clears the chat reservation`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val gate = object : StudioSubmissionGate {
            override val isCancelled = false
            override suspend fun begin() = Unit
            override suspend fun awaitRevocation(): CancellationException? = null
            override suspend fun cancel(): Boolean = throw CancellationException("Persistence interrupted")
        }
        host.execute = { RunOutcome.Completed }
        assertFailsWith<CancellationException> {
            coordinator.run(host, runRequest("failed").copy(submission = gate))
        }
        assertTrue("finished" in events)
        assertEquals(RunOutcome.Completed, coordinator.run(host, runRequest("next")))
    }
}
