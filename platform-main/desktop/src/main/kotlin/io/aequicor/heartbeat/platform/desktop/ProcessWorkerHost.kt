package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Host seam: tests use durable fakes, the desktop implementation assembles existing profile services. */
internal interface ProcessWorkerSession {
    suspend fun open()
    val status: Flow<WorkerSnapshot>
    suspend fun awaitRecovery()
    suspend fun shutdown(): Boolean
}

/** A retained intervention cannot silently create a fresh graph on restart. */
internal suspend fun runStoredProcessWorker(
    waiting: WorkerProblem?,
    stop: CompletableDeferred<Unit>,
    state: MutableStateFlow<WorkerSnapshot>,
    createSession: () -> ProcessWorkerSession,
    persistWait: suspend (WorkerProblem) -> Unit,
): Boolean {
    if (waiting != null) {
        state.value = WorkerSnapshot(WorkerPhase.Waiting, waiting)
        stop.await()
        return true
    }
    return superviseProcessWorker(createSession(), stop, state, persistWait)
}

/**
 * The storage lease and control plane outlive this routine. A native request pauses the profile after recording
 * a host-owned reason; no request is approved. Process decisions alone only affect the status. Stop preserves
 * the selected profile, persisted waits and workflow journals for the next host's ordinary recovery.
 */
internal suspend fun superviseProcessWorker(
    session: ProcessWorkerSession,
    stop: CompletableDeferred<Unit>,
    state: MutableStateFlow<WorkerSnapshot>,
    persistWait: suspend (WorkerProblem) -> Unit,
    recoveryTimeoutMillis: Long = 30.seconds.inWholeMilliseconds,
): Boolean {
    var wait = WorkerProblem.None
    var isClean = false
    try {
        wait = runWorkerUntilStopped(session, stop, state, recoveryTimeoutMillis)
        if (wait != WorkerProblem.None) persistWait(wait)
    } finally {
        state.update { it.copy(phase = WorkerPhase.Stopping) }
        withContext(NonCancellable) { isClean = session.shutdown() }
    }
    if (!isClean) {
        persistWait(WorkerProblem.CleanupFailed)
        state.value = WorkerSnapshot(WorkerPhase.Failed, WorkerProblem.CleanupFailed)
        return false
    }
    if (wait != WorkerProblem.None) {
        state.value = WorkerSnapshot(WorkerPhase.Waiting, wait)
        Log.tag("ProcessWorker").i { "Worker is paused; host intervention reason=$wait" }
        stop.await()
    }
    return true
}

private suspend fun runWorkerUntilStopped(
    session: ProcessWorkerSession,
    stop: CompletableDeferred<Unit>,
    state: MutableStateFlow<WorkerSnapshot>,
    recoveryTimeoutMillis: Long,
): WorkerProblem = try {
    coroutineScope {
        val activity = async { driveProcessWorker(session, state, recoveryTimeoutMillis) }
        select {
            stop.onAwait { activity.cancelAndJoin() }
            activity.onAwait { }
        }
    }
    WorkerProblem.None
} catch (error: WorkerStartRefused) {
    Log.tag("ProcessWorker").w(error) { "Worker startup requires host configuration" }
    error.problem
} catch (error: WorkerInteractionRequired) {
    Log.tag("ProcessWorker").w(error) { "Worker requires an interactive host" }
    WorkerProblem.InteractionRequired
} catch (error: WorkerRecoveryTimedOut) {
    Log.tag("ProcessWorker").w(error) { "Worker initial recovery timed out" }
    WorkerProblem.RecoveryFailed
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Log.tag("ProcessWorker").e(error) { "Worker profile could not start or recover" }
    WorkerProblem.StorageUnavailable
}

private suspend fun driveProcessWorker(
    session: ProcessWorkerSession,
    state: MutableStateFlow<WorkerSnapshot>,
    recoveryTimeoutMillis: Long,
): Unit = coroutineScope {
    var isRecovered = false
    session.open()
    val observations = session.status
    state.value = WorkerSnapshot(WorkerPhase.Recovering)
    launch {
        observations.collect { snapshot ->
            checkWorkerObservation(snapshot)
            state.value = if (isRecovered) snapshot else snapshot.copy(phase = WorkerPhase.Recovering)
        }
    }
    val isReady = withTimeoutOrNull(recoveryTimeoutMillis) {
        session.awaitRecovery()
        true
    }
    if (isReady != true) throw WorkerRecoveryTimedOut()
    isRecovered = true
    state.update { it.copy(phase = if (it.decisions > 0) WorkerPhase.Waiting else WorkerPhase.Running) }
    Log.tag("ProcessWorker").i { "Worker initial recovery barriers completed" }
    awaitCancellation()
}

private class WorkerInteractionRequired : IllegalStateException("Worker requires an interactive host")
private class WorkerRecoveryTimedOut : IllegalStateException("Worker initial recovery timed out")
internal class WorkerStartRefused(val problem: WorkerProblem) : IllegalStateException("Worker startup refused")

private fun checkWorkerObservation(snapshot: WorkerSnapshot) {
    if (snapshot.isHostRequired) {
        throw WorkerInteractionRequired()
    }
    check(snapshot.problem != WorkerProblem.RecoveryFailed) { "Profile recovery is unavailable" }
}
