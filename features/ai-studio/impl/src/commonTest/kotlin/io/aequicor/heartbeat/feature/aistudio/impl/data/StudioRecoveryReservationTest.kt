package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudioRecoveryReservationTest {
    private val helper = HelperId("chat")
    private val source = RequestId("R")
    private val request = runRequest("Q").copy(
        recoveryRequests = listOf(source),
        origin = StudioTurnOrigin.GraphWork(source),
    )
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "native")
    private val witness = StudioRecoveryWitness(request.request, source, setOf(source), session)

    @Test
    fun `recovery publication waits for the route without holding the coordinator lock`() = runTest {
        val coordinator = StudioRunCoordinator(RunProfile(backgroundScope), RunClock)
        val routes = StudioHelperRoutes()
        val attempts = StudioHelperAttempts(ChecklistTestStores(), routes)
        val commit = CompletableDeferred<Unit>()
        val replacement = async { routes.withLock(helper) { commit.await() } }
        runCurrent()
        val events = mutableListOf<String>()
        val terminal = CompletableDeferred<RunOutcome>()
        val delegate = RunHost(events).apply {
            execute = {
                assertFailsWith<CancellationException> { attempts.submitRecovery(helper, request.request) }
                terminal.await()
            }
        }
        val host = object : StudioRecoveryRunHost, StudioRunHost by delegate {
            override suspend fun reserveRecovery(request: StudioTurnRequest) {
                coordinator.mutate("unrelated") { events += "unrelated" }
                attempts.prepareRecovery(helper, witness)
            }
            override suspend fun recordRecoveryStop(request: StudioTurnRequest) {
                attempts.stopRecovery(helper, request.request)
            }
        }
        val running = async { coordinator.run(host, request) }
        runCurrent()
        assertTrue(coordinator.isBusy("chat"))
        assertNull(coordinator.currentRequest("chat"))
        assertEquals(listOf("unrelated"), events)
        val stop = async {
            val target = checkNotNull(coordinator.requestRecoveryStop("chat"))
            attempts.stopRecovery(helper, target)
        }
        runCurrent()
        assertFalse(stop.isCompleted)
        commit.complete(Unit)
        replacement.await()
        val stopped = stop.await()
        assertTrue(stopped.isStopRequested)
        assertEquals(request.request, coordinator.currentRequest("chat"))
        assertNull(attempts.receipt(helper, request.request))
        assertTrue(coordinator.isBusy("chat"))
        terminal.complete(RunOutcome.Completed)
        running.await()
        assertFalse(coordinator.isBusy("chat"))
        assertNull(coordinator.requestRecoveryStop("chat"))
    }

    @Test
    fun `failed recovery initialization releases waiting Stop without publishing or executing Q`() = runTest {
        val profile = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]),
        )
        val coordinator = StudioRunCoordinator(RunProfile(profile), RunClock)
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val delegate = RunHost(events)
        val host = object : StudioRecoveryRunHost, StudioRunHost by delegate {
            override suspend fun reserveRecovery(request: StudioTurnRequest) {
                release.await()
                error("Journal unavailable")
            }
            override suspend fun recordRecoveryStop(request: StudioTurnRequest) = Unit
        }
        val running = async {
            assertFailsWith<IllegalStateException> { coordinator.run(host, request) }
        }
        runCurrent()
        val stop = async { coordinator.requestRecoveryStop("chat") }
        runCurrent()
        assertFalse(stop.isCompleted)
        release.complete(Unit)
        running.await()
        assertEquals(request.request, stop.await())
        assertNull(coordinator.currentRequest("chat"))
        assertFalse(coordinator.isBusy("chat"))
        assertEquals(listOf("finished"), events)
        assertEquals(RunOutcome.Completed, coordinator.run(delegate, runRequest("later")))
        assertEquals(request.request, stop.await())
    }

    @Test
    fun `caller cancellation during recovery initialization installs cleanup before publication`() = runTest {
        val coordinator = StudioRunCoordinator(RunProfile(backgroundScope), RunClock)
        val routes = StudioHelperRoutes()
        val attempts = StudioHelperAttempts(ChecklistTestStores(), routes)
        val commit = CompletableDeferred<Unit>()
        val replacement = async { routes.withLock(helper) { commit.await() } }
        runCurrent()
        val events = mutableListOf<String>()
        val delegate = RunHost(events)
        val host = object : StudioRecoveryRunHost, StudioRunHost by delegate {
            override suspend fun reserveRecovery(request: StudioTurnRequest) {
                attempts.prepareRecovery(helper, witness)
            }
            override suspend fun recordRecoveryStop(request: StudioTurnRequest) {
                attempts.stopRecovery(helper, request.request)
            }
        }
        val running = async { coordinator.run(host, request, cancelBeforeSubmission = true) }
        runCurrent()
        val stop = async { coordinator.requestRecoveryStop("chat") }
        runCurrent()
        running.cancelAndJoin()
        assertEquals(request.request, stop.await())
        assertNull(coordinator.currentRequest("chat"))
        assertFalse(coordinator.isBusy("chat"))
        assertNull(attempts.recovery(helper, request.request))
        assertNull(attempts.receipt(helper, request.request))
        assertEquals(listOf("finished"), events)
        commit.complete(Unit)
        replacement.await()
    }
}
