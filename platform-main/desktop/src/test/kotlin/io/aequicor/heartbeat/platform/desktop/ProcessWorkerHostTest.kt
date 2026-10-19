package io.aequicor.heartbeat.platform.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessWorkerHostTest {
    @Test
    fun `restart with an intervention marker never recreates the graph or repeats an action`() = runTest {
        val state = MutableStateFlow(WorkerSnapshot(WorkerPhase.Starting))
        val stop = CompletableDeferred<Unit>()
        val worker = async {
            runStoredProcessWorker(
                WorkerProblem.InteractionRequired,
                stop,
                state,
                createSession = { error("Must not create a graph or replay work") },
                persistWait = { error("Must retain the existing marker") },
            )
        }
        runCurrent()
        assertEquals(WorkerPhase.Waiting, state.value.phase)
        assertFalse(worker.isCompleted)
        stop.complete(Unit)
        assertTrue(worker.await())
    }

    @Test
    fun `worker does not report running before recovery and stop preserves the saved work`() = runTest {
        val session = FakeWorkerSession()
        val stop = CompletableDeferred<Unit>()
        val state = MutableStateFlow(WorkerSnapshot(WorkerPhase.Starting))
        val worker = async { superviseProcessWorker(session, stop, state, { error("No wait expected") }) }
        runCurrent()
        assertEquals(WorkerPhase.Recovering, state.value.phase)
        session.ready.complete(Unit)
        runCurrent()
        assertEquals(WorkerPhase.Running, state.value.phase)
        stop.complete(Unit)
        assertTrue(worker.await())
        assertEquals(listOf("open", "recover", "shutdown"), session.events)
        assertTrue(session.savedWork)
    }

    @Test
    fun `stop during startup cancels recovery and awaits cleanup before returning`() = runTest {
        val session = FakeWorkerSession()
        session.cleanup = CompletableDeferred()
        val stop = CompletableDeferred<Unit>()
        val state = MutableStateFlow(WorkerSnapshot(WorkerPhase.Starting))
        val worker = async { superviseProcessWorker(session, stop, state, {}) }
        runCurrent()
        stop.complete(Unit)
        runCurrent()
        assertFalse(worker.isCompleted)
        assertEquals(WorkerPhase.Stopping, state.value.phase)
        session.cleanup?.complete(true)
        assertTrue(worker.await())
        assertFalse(session.ready.isCompleted)
    }

    @Test
    fun `native interaction is persisted before releasing executors and leaves an explicit waiting host`() = runTest {
        val session = FakeWorkerSession()
        session.ready.complete(Unit)
        val stop = CompletableDeferred<Unit>()
        val state = MutableStateFlow(WorkerSnapshot(WorkerPhase.Starting))
        val worker = async {
            superviseProcessWorker(session, stop, state, { reason -> session.events += "persist-$reason" })
        }
        runCurrent()
        session.status.value = WorkerSnapshot(
            WorkerPhase.Waiting,
            WorkerProblem.InteractionRequired,
            isHostRequired = true,
        )
        runCurrent()
        assertEquals(WorkerPhase.Waiting, state.value.phase)
        assertEquals(WorkerProblem.InteractionRequired, state.value.problem)
        assertTrue(session.events.indexOf("persist-InteractionRequired") < session.events.indexOf("shutdown"))
        assertFalse(worker.isCompleted)
        stop.complete(Unit)
        assertTrue(worker.await())
    }

    @Test
    fun `pending process decisions are visible without granting them or stopping unrelated work`() = runTest {
        val session = FakeWorkerSession()
        session.ready.complete(Unit)
        val stop = CompletableDeferred<Unit>()
        val state = MutableStateFlow(WorkerSnapshot(WorkerPhase.Starting))
        val worker = async { superviseProcessWorker(session, stop, state, { error("No global pause expected") }) }
        runCurrent()
        session.status.value = WorkerSnapshot(WorkerPhase.Waiting, WorkerProblem.InteractionRequired, decisions = 2)
        runCurrent()
        assertEquals(2, state.value.decisions)
        assertFalse("shutdown" in session.events)
        stop.complete(Unit)
        assertTrue(worker.await())
    }

    @Test
    fun `recovery timeout never becomes ready and closes the profile instead of retaining agents`() = runTest {
        val session = FakeWorkerSession()
        val stop = CompletableDeferred<Unit>()
        val state = MutableStateFlow(WorkerSnapshot(WorkerPhase.Starting))
        val reasons = mutableListOf<WorkerProblem>()
        val worker = async { superviseProcessWorker(session, stop, state, reasons::add, recoveryTimeoutMillis = 1000) }
        runCurrent()
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(listOf(WorkerProblem.RecoveryFailed), reasons)
        assertEquals(WorkerPhase.Waiting, state.value.phase)
        assertTrue("shutdown" in session.events)
        stop.complete(Unit)
        assertTrue(worker.await())
    }

    @Test
    fun `unconfirmed cleanup records a failure and cannot report a successful stop`() = runTest {
        val session = FakeWorkerSession()
        session.cleanup = CompletableDeferred(false)
        val stop = CompletableDeferred<Unit>()
        val state = MutableStateFlow(WorkerSnapshot(WorkerPhase.Starting))
        val reasons = mutableListOf<WorkerProblem>()
        val worker = async { superviseProcessWorker(session, stop, state, reasons::add) }
        runCurrent()
        stop.complete(Unit)
        assertFalse(worker.await())
        assertEquals(WorkerPhase.Failed, state.value.phase)
        assertEquals(listOf(WorkerProblem.CleanupFailed), reasons)
    }
}

private class FakeWorkerSession : ProcessWorkerSession {
    val events = mutableListOf<String>()
    val ready = CompletableDeferred<Unit>()
    override val status = MutableStateFlow(WorkerSnapshot(WorkerPhase.Running))
    var cleanup: CompletableDeferred<Boolean>? = null
    val savedWork = true

    override suspend fun open() {
        events += "open"
    }
    override suspend fun awaitRecovery() {
        events += "recover"
        ready.await()
    }
    override suspend fun shutdown(): Boolean {
        events += "shutdown"
        return cleanup?.await() ?: true
    }
}
