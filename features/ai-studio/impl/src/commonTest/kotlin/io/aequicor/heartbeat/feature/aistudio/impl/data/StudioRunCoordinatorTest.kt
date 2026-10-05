package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StudioRunCoordinatorTest {
    @Test
    fun `cancelling scheduled preparation releases the reservation without later submission`() = runTest {
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
        val caller = async { coordinator.run(host, runRequest("wake"), cancelBeforeSubmission = true) }
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
}

private class RunHost(private val events: MutableList<String>) : StudioRunHost {
    var execute: suspend (StudioTurnRequest) -> RunOutcome = { RunOutcome.Completed }
    var cleanup: suspend () -> Unit = {}
    override suspend fun startedRun(id: String, at: Instant) {
        events += "started"
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

private class RunProfile(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "test/profile"
    override val savedState: ScopeSavedState get() = error("Unused")
    override val isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
}

private object RunClock : Clock {
    override fun now() = Instant.DISTANT_PAST
}

private fun runRequest(request: String) = StudioTurnRequest(
    "chat",
    "Implement the task",
    RunSettings("model", ReasoningEffort.Medium, ApprovalMode.Ask),
    WorktreeRunKind.Coding,
    RequestId(request),
)
