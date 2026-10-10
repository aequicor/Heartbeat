package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDeferredException
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDroppedException
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StudioRunCoordinatorTest {
    @Test
    fun `owner cancellation at begin clears the unsubmitted receipt and leaves the chat reusable`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val inbox = StudioWakeInbox(ChecklistTestStores())
        val request = runRequest("cancelled")
        var isCancelling = false
        val admission = flow {
            if (isCancelling) throw CancellationException("Owner cancelled")
            emit(ScheduledWakeAdmission.Allow)
        }
        host.execute = { actual ->
            isCancelling = true
            actual.submission?.begin()
            events += "submitted"
            RunOutcome.Completed
        }
        assertFailsWith<CancellationException> {
            coordinator.run(
                host,
                request,
                beforeExecute = { inbox.submitting(request.request) },
                onCancelledBeforeSubmission = { inbox.cancelledBeforeSubmission(request.request) },
                admission = admission,
            )
        }
        assertNull(inbox.receipt(request.request))
        assertFalse("submitted" in events)
        assertEquals(listOf("started", "execute", "finished"), events)
        host.execute = { RunOutcome.Completed }
        assertEquals(RunOutcome.Completed, coordinator.run(host, runRequest("next")))
    }

    @Test
    fun `owner drop after submission leaves accepted work owned by the profile`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val admission = MutableStateFlow(ScheduledWakeAdmission.Allow)
        val finished = CompletableDeferred<Unit>()
        host.execute = { request ->
            request.submission?.begin()
            events += "submitted"
            finished.await()
            RunOutcome.Completed
        }
        val caller = async { coordinator.run(host, runRequest("wake"), admission = admission) }
        runCurrent()
        assertTrue("submitted" in events)
        admission.value = ScheduledWakeAdmission.Drop
        runCurrent()
        assertFalse(caller.isCompleted)
        assertFalse("finished" in events)
        finished.complete(Unit)
        assertEquals(RunOutcome.Completed, caller.await())
    }

    @Test
    fun `owner drop while waiting for a busy chat never reserves another execution`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val finished = CompletableDeferred<Unit>()
        host.execute = {
            finished.await()
            RunOutcome.Completed
        }
        val active = async { coordinator.run(host, runRequest("same")) }
        runCurrent()
        val admission = MutableStateFlow(ScheduledWakeAdmission.Allow)
        val waiting = async {
            assertFailsWith<ScheduledWakeDroppedException> {
                coordinator.run(host, runRequest("same"), waitForIdle = true, admission = admission)
            }
        }
        runCurrent()
        admission.value = ScheduledWakeAdmission.Drop
        runCurrent()
        waiting.await()
        assertEquals(1, events.count { it == "started" })
        assertFalse(active.isCompleted)
        finished.complete(Unit)
        active.await()
    }

    @Test
    fun `owner drop during preparation clears receipt and permanently revokes this attempt`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val inbox = StudioWakeInbox(ChecklistTestStores())
        val admission = MutableStateFlow(ScheduledWakeAdmission.Allow)
        val prepared = CompletableDeferred<Unit>()
        val request = runRequest("dropped")
        host.execute = { actual ->
            prepared.await()
            actual.submission?.begin()
            events += "submitted"
            RunOutcome.Completed
        }
        val caller = async {
            assertFailsWith<ScheduledWakeDroppedException> {
                coordinator.run(
                    host,
                    request,
                    cancelBeforeSubmission = true,
                    beforeExecute = { inbox.submitting(request.request) },
                    onCancelledBeforeSubmission = { inbox.cancelledBeforeSubmission(request.request) },
                    admission = admission,
                )
            }
        }
        runCurrent()
        assertEquals(WakeReceipt.Submitting, inbox.receipt(request.request))
        admission.value = ScheduledWakeAdmission.Drop
        runCurrent()
        caller.await()
        assertNull(inbox.receipt(request.request))
        admission.value = ScheduledWakeAdmission.Allow
        prepared.complete(Unit)
        runCurrent()
        assertFalse("submitted" in events)
        assertEquals(listOf("started", "execute", "finished"), events)
        assertEquals(RunOutcome.Completed, coordinator.run(host, runRequest("next")))
    }

    @Test
    fun `drop at the native boundary refuses even before the admission collector runs`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val admission = MutableStateFlow(ScheduledWakeAdmission.Allow)
        host.execute = { request ->
            admission.value = ScheduledWakeAdmission.Drop
            request.submission?.begin()
            events += "submitted"
            RunOutcome.Completed
        }
        assertFailsWith<ScheduledWakeDroppedException> {
            coordinator.run(host, runRequest("wake"), admission = admission)
        }
        assertFalse("submitted" in events)
        assertEquals(listOf("started", "execute", "finished"), events)
    }

    @Test
    fun `cancelling gated scheduled preparation releases the reservation without later submission`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val prepared = CompletableDeferred<Unit>()
        host.execute = { request ->
            prepared.await()
            request.submission?.begin()
            events += "submitted"
            RunOutcome.Completed
        }
        val caller = async {
            coordinator.run(
                host,
                runRequest("wake"),
                cancelBeforeSubmission = true,
                admission = MutableStateFlow(ScheduledWakeAdmission.Allow),
            )
        }
        runCurrent()
        assertEquals(listOf("started", "execute"), events)
        caller.cancelAndJoin()
        runCurrent()
        assertEquals(listOf("started", "execute", "finished"), events)
        prepared.complete(Unit)
        runCurrent()
        assertFalse("submitted" in events)
        assertEquals(RunOutcome.Completed, coordinator.run(host, runRequest("next")))
    }

    @Test
    fun `disabling during preparation clears its receipt and retries the same wake after enabling`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val stores = ChecklistTestStores()
        val inbox = StudioWakeInbox(stores)
        val enabled = MutableStateFlow(true)
        val prepared = CompletableDeferred<Unit>()
        val request = runRequest("wake").copy(onAccepted = { inbox.accepted(RequestId("wake")) })
        host.execute = { actual ->
            prepared.await()
            actual.submission?.begin()
            events += "submitted"
            actual.onAccepted()
            RunOutcome.Completed
        }
        suspend fun runWake() = coordinator.run(
            host,
            request,
            cancelBeforeSubmission = true,
            beforeExecute = { inbox.submitting(request.request) },
            onCancelledBeforeSubmission = { inbox.cancelledBeforeSubmission(request.request) },
            admission = enabled.asAdmission(),
        )
        val first = async { assertFailsWith<ScheduledWakeDeferredException> { runWake() } }
        runCurrent()
        assertEquals(WakeReceipt.Submitting, inbox.receipt(request.request))
        enabled.value = false
        runCurrent()
        first.await()
        assertNull(StudioWakeInbox(stores).receipt(request.request))
        assertEquals(listOf("started", "execute", "finished"), events)
        prepared.complete(Unit)
        runCurrent()
        assertFalse("submitted" in events)
        enabled.value = true
        assertEquals(RunOutcome.Completed, runWake())
        assertEquals(1, events.count { it == "submitted" })
        assertEquals(WakeReceipt.Accepted, StudioWakeInbox(stores).receipt(request.request))
    }

    @Test
    fun `disabling while reserving still releases the reservation before another run`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val enabled = MutableStateFlow(true)
        host.start = { enabled.value = false }
        host.execute = { request ->
            request.submission?.begin()
            events += "submitted"
            RunOutcome.Completed
        }
        assertFailsWith<ScheduledWakeDeferredException> {
            coordinator.run(host, runRequest("wake"), cancelBeforeSubmission = true, admission = enabled.asAdmission())
        }
        assertEquals(listOf("started", "execute", "finished"), events)
        host.start = {}
        enabled.value = true
        assertEquals(
            RunOutcome.Completed,
            coordinator.run(host, runRequest("next"), cancelBeforeSubmission = true, admission = enabled.asAdmission()),
        )
        assertEquals(1, events.count { it == "submitted" })
    }

    @Test
    fun `submission rechecks the flag before the toggle collector gets a dispatcher turn`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val enabled = MutableStateFlow(true)
        var cleanupCount = 0
        host.execute = { request ->
            enabled.value = false
            request.submission?.begin()
            events += "submitted"
            RunOutcome.Completed
        }
        assertFailsWith<ScheduledWakeDeferredException> {
            coordinator.run(
                host,
                runRequest("wake"),
                cancelBeforeSubmission = true,
                onCancelledBeforeSubmission = { cleanupCount++ },
                admission = enabled.asAdmission(),
            )
        }
        assertEquals(listOf("started", "execute", "finished"), events)
        assertEquals(1, cleanupCount)
    }

    @Test
    fun `disabling after native submission preserves unknown acceptance and the profile owned run`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val inbox = StudioWakeInbox(ChecklistTestStores())
        val enabled = MutableStateFlow(true)
        val nativeResponse = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<RunOutcome>()
        val request = runRequest("wake").copy(onAccepted = { inbox.accepted(RequestId("wake")) })
        host.execute = { actual ->
            actual.submission?.begin()
            events += "submitted"
            nativeResponse.await()
            actual.onAccepted()
            terminal.await()
        }
        val caller = async {
            coordinator.run(
                host,
                request,
                cancelBeforeSubmission = true,
                beforeExecute = { inbox.submitting(request.request) },
                onCancelledBeforeSubmission = { inbox.cancelledBeforeSubmission(request.request) },
                admission = enabled.asAdmission(),
            )
        }
        runCurrent()
        enabled.value = false
        runCurrent()
        assertFalse(caller.isCompleted)
        assertEquals(WakeReceipt.Submitting, inbox.receipt(request.request))
        assertEquals(listOf("started", "execute", "submitted"), events)
        nativeResponse.complete(Unit)
        runCurrent()
        assertEquals(WakeReceipt.Accepted, inbox.receipt(request.request))
        assertFalse("finished" in events)
        terminal.complete(RunOutcome.Completed)
        assertEquals(RunOutcome.Completed, caller.await())
        assertEquals(WakeReceipt.Accepted, inbox.receipt(request.request))
    }

    @Test
    fun `cancelling a scheduled waiter preserves an accepted native turn`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val terminal = CompletableDeferred<RunOutcome>()
        var accepted = 0
        host.execute = { request ->
            request.submission?.begin()
            request.onAccepted()
            terminal.await()
        }
        val caller = async {
            coordinator.run(
                host,
                runRequest("wake").copy(onAccepted = { accepted++ }),
                cancelBeforeSubmission = true,
            )
        }
        runCurrent()
        assertEquals(1, accepted)
        caller.cancelAndJoin()
        runCurrent()
        assertEquals(listOf("started", "execute"), events)
        terminal.complete(RunOutcome.Completed)
        runCurrent()
        assertEquals(listOf("started", "execute", "finished"), events)
    }

    @Test
    fun `cancellation racing native submission preserves its later acceptance and observation`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val nativeResponse = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<RunOutcome>()
        var accepted = 0
        host.execute = { request ->
            request.submission?.begin()
            events += "submitted"
            nativeResponse.await()
            request.onAccepted()
            terminal.await()
        }
        val caller = async {
            coordinator.run(
                host,
                runRequest("wake").copy(onAccepted = { accepted++ }),
                cancelBeforeSubmission = true,
            )
        }
        runCurrent()
        caller.cancelAndJoin()
        runCurrent()
        assertEquals(0, accepted)
        assertEquals(listOf("started", "execute", "submitted"), events)
        nativeResponse.complete(Unit)
        runCurrent()
        assertEquals(1, accepted)
        assertFalse("finished" in events)
        terminal.complete(RunOutcome.Completed)
        runCurrent()
        assertTrue("finished" in events)
    }

    @Test
    fun `cancelling a scheduled wake waiting for a busy chat never reserves it`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val terminal = CompletableDeferred<RunOutcome>()
        host.execute = { terminal.await() }
        val first = async { coordinator.run(host, runRequest("coding")) }
        runCurrent()
        val wake = async {
            coordinator.run(host, runRequest("wake"), waitForIdle = true, cancelBeforeSubmission = true)
        }
        runCurrent()
        wake.cancelAndJoin()
        terminal.complete(RunOutcome.Completed)
        assertEquals(RunOutcome.Completed, first.await())
        runCurrent()
        assertEquals(listOf("started", "execute", "finished"), events)
    }

    @Test
    fun `closing the attachment waiter preserves reserved files and native acknowledgement in the profile`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val terminal = CompletableDeferred<RunOutcome>()
        var acknowledgements = 0
        val request = runRequest("with-files").copy(
            attachments = listOf(ResourceRef("attachment:image", "image/png")),
            onAccepted = { acknowledgements++ },
        )
        var submitted: StudioTurnRequest? = null
        host.execute = { actual ->
            submitted = actual
            actual.onAccepted()
            terminal.await()
        }
        val caller = async { coordinator.run(host, request) }
        runCurrent()
        assertEquals(request, submitted)
        assertEquals(1, acknowledgements)
        caller.cancelAndJoin()
        assertFalse(terminal.isCancelled)
        assertEquals(listOf("started", "execute"), events)
        terminal.complete(RunOutcome.Completed)
        runCurrent()
        assertEquals(listOf("started", "execute", "finished"), events)
        assertEquals(1, acknowledgements)
        assertEquals(RunOutcome.Completed, coordinator.run(host, runRequest("next")))
    }

    @Test
    fun `action handoff reserves the chat before invoking its durable callback`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val handoff = CompletableDeferred<Unit>()
        val action = async {
            coordinator.run(host, runRequest("action"), beforeExecute = {
                events += "handoff"
                handoff.await()
            })
        }
        runCurrent()
        assertEquals(listOf("started", "handoff"), events)
        assertFailsWith<IllegalStateException> { coordinator.run(host, runRequest("competing")) }
        assertEquals(listOf("started", "handoff"), events)

        handoff.complete(Unit)
        assertEquals(RunOutcome.Completed, action.await())
        assertEquals(listOf("started", "handoff", "execute", "finished"), events)
    }

    @Test
    fun `cancelling the UI waiter preserves the profile-owned accepted execution`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val terminal = CompletableDeferred<RunOutcome>()
        host.execute = { terminal.await() }
        val caller = async { coordinator.run(host, runRequest("coding")) }
        runCurrent()
        assertEquals(listOf("started", "execute"), events)

        caller.cancelAndJoin()
        runCurrent()
        assertFalse(terminal.isCancelled)
        assertEquals(listOf("started", "execute"), events)
        terminal.complete(RunOutcome.Completed)
        runCurrent()
        assertEquals(listOf("started", "execute", "finished"), events)
        assertEquals(RunOutcome.Completed, coordinator.run(host, runRequest("next")))
    }

    @Test
    fun `waiting action cannot reserve the chat before the preceding cleanup finishes`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(events)
        val cleanup = CompletableDeferred<Unit>()
        var cleanupCalls = 0
        host.cleanup = { if (++cleanupCalls == 1) cleanup.await() }
        val first = async { coordinator.run(host, runRequest("coding")) }
        runCurrent()
        val second = async {
            coordinator.run(host, runRequest("action"), waitForIdle = true, beforeExecute = { events += "handoff" })
        }
        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals(listOf("started", "execute", "finished"), events)

        cleanup.complete(Unit)
        assertEquals(RunOutcome.Completed, first.await())
        assertEquals(RunOutcome.Completed, second.await())
        assertEquals(listOf("started", "execute", "finished", "started", "handoff", "execute", "finished"), events)
    }

    @Test
    fun `disabled continuation waits without reserving chat and resumes when enabled`() = runTest {
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(mutableListOf())
        val terminal = CompletableDeferred<RunOutcome>()
        val executed = mutableListOf<String>()
        host.execute = {
            executed += it.request.value
            if (it.request.value == "first") terminal.await() else RunOutcome.Completed
        }
        val first = async { coordinator.run(host, runRequest("first")) }
        runCurrent()
        val enabled = MutableStateFlow(true)
        val wake = async {
            assertFailsWith<ScheduledWakeDeferredException> {
                coordinator.run(host, runRequest("wake"), waitForIdle = true, admission = enabled.asAdmission())
            }
        }
        runCurrent()
        enabled.value = false
        terminal.complete(RunOutcome.Completed)
        first.await()
        runCurrent()
        wake.await()
        assertEquals(RunOutcome.Completed, coordinator.run(host, runRequest("manual")))
        assertEquals(listOf("first", "manual"), executed)
        enabled.value = true
        assertEquals(
            RunOutcome.Completed,
            coordinator.run(host, runRequest("wake"), waitForIdle = true, admission = enabled.asAdmission()),
        )
        assertEquals(listOf("first", "manual", "wake"), executed)
    }
}

internal class RunHost(private val events: MutableList<String>) : StudioRunHost {
    var execute: suspend (StudioTurnRequest) -> RunOutcome = { RunOutcome.Completed }
    var cleanup: suspend () -> Unit = {}
    var start: suspend () -> Unit = {}
    override suspend fun startedRun(id: String, at: Instant) {
        events += "started"
        start()
    }
    override suspend fun executeRun(request: StudioTurnRequest): RunOutcome {
        events += "execute"
        return execute(request)
    }
    override suspend fun finishedRun(id: String) {
        events += "finished"
        cleanup()
    }
}

internal class RunProfile(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "test/profile"
    override val savedState: ScopeSavedState get() = error("Unused")
    override val isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
}

internal object RunClock : Clock {
    override fun now() = Instant.DISTANT_PAST
}

internal fun runRequest(request: String) = StudioTurnRequest(
    "chat",
    "Implement the task",
    RunSettings("model", ReasoningEffort.Medium, ApprovalMode.Ask),
    WorktreeRunKind.Coding,
    RequestId(request),
)

private fun Flow<Boolean>.asAdmission(): Flow<ScheduledWakeAdmission> = map {
    if (it) ScheduledWakeAdmission.Allow else ScheduledWakeAdmission.Defer
}
