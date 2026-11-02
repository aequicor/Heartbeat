package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioConversationReservationTest {
    @Test
    fun `stop targets the reserved request before journal preparation instead of the previous turn`() = runTest {
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val request = runRequest("new")
        val observations = ReservationObservations()
        val stops = StudioConversationStops(observations, coordinator)
        val root = LogicalSessionContext(LogicalSessionId("root"), SessionKind.Session)
        val context = root.subsession(LogicalSessionId(request.id))
        val record = StudioChatRecord(
            request.id,
            "Child",
            RunClock.now(),
            lastRunRequest = RequestId("old"),
            identity = StudioSessionIdentity(StudioIdentityOrigin.AgentCreated, context),
        )
        val host = RunHost(mutableListOf()).apply {
            execute = {
                started.complete(Unit)
                resume.await()
                RunOutcome.Completed
            }
        }
        val run = async { coordinator.run(host, request) }
        started.await()
        assertTrue(stops.cancel(record))
        assertEquals(listOf(request.request), observations.stopped)
        // A root's very first journaled send must be fenced before its writer marker is saved too.
        assertTrue(stops.cancel(record.copy(identity = null)))
        assertEquals(listOf(request.request, request.request), observations.stopped)
        resume.complete(Unit)
        run.await()
        assertNull(coordinator.currentRequest(request.id))
        assertFalse(coordinator.isBusy(request.id))
    }

    @Test
    fun `unjournaled recovery does not turn stopping native R into NotSubmitted Q`() = runTest {
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val source = RequestId("native-R")
        val request = runRequest("observer-Q").copy(
            recoveryRequests = listOf(source),
            origin = StudioTurnOrigin.GraphWork(source),
        )
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val observations = ReservationObservations()
        val stops = StudioConversationStops(observations, coordinator)
        val host = RunHost(mutableListOf()).apply {
            execute = {
                started.complete(Unit)
                resume.await()
                RunOutcome.Completed
            }
        }
        val record = StudioChatRecord(request.id, "Root", RunClock.now(), lastRunRequest = source)
        val run = async { coordinator.run(host, request) }
        started.await()

        assertFalse(stops.cancel(record))
        assertTrue(observations.stopped.isEmpty())
        assertTrue(stops.cancel(record.copy(hasAttemptJournal = true)))
        assertEquals(listOf(source), observations.stopped)
        assertEquals(request.request, coordinator.currentRequest(record.id))
        assertNull(coordinator.currentSendRequest(record.id))
        resume.complete(Unit)
        run.await()
    }

    @Test
    fun `a failed or cancelled start removes its exact reservation and permits a later request`() = runTest {
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val request = runRequest("failed")
        val host = RunHost(mutableListOf()).apply {
            start = {
                assertEquals(request.request, coordinator.currentRequest(request.id))
                throw CancellationException("Start was cancelled")
            }
        }
        assertFailsWith<CancellationException> { coordinator.run(host, request) }
        assertNull(coordinator.currentRequest(request.id))
        assertFalse(coordinator.isBusy(request.id))
        host.start = {}
        assertEquals(RunOutcome.Completed, coordinator.run(host, runRequest("next")))
        assertNull(coordinator.currentRequest(request.id))
    }
}

private class ReservationObservations : StudioConversationObservations {
    val stopped = mutableListOf<RequestId>()
    override suspend fun snapshot(record: StudioChatRecord): HostConversationSnapshot =
        error("The exact reservation must take precedence over previous recorded state")
    override suspend fun result(record: StudioChatRecord, request: RequestId): HostConversationResult? = null
    override suspend fun cancel(record: StudioChatRecord, request: RequestId): HostConversationCancellation {
        stopped += request
        return HostConversationCancellation.NotSubmitted(request)
    }
}
