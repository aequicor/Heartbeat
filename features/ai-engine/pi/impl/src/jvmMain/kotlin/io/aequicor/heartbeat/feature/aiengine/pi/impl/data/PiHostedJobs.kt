package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Main-dispatcher-confined ownership of hosted calls and questionnaires. Revocation closes admission immediately,
 * but cancelled parents remain reachable until all children actually finish, including non-cancellable cleanup.
 * Ordinary completion and explicit cancellation share this barrier; neither may forget an undrained child.
 */
internal class PiHostedJobs(private val scope: CoroutineScope, private val timeoutMillis: Long = DRAIN_TIMEOUT) {
    private val jobs = mutableMapOf<TurnId, CompletableJob>()
    private val closed = mutableSetOf<TurnId>()

    /** Cancelling is still pending: non-cancellable child cleanup must finish before session retirement. */
    val hasPending: Boolean get() = jobs.values.any { !it.isCompleted }

    fun isClosed(turn: TurnId): Boolean = turn in closed
    fun lifetime(turn: TurnId): Job? = jobs[turn]

    /** Call only after rechecking that this turn is still current, following every suspending admission check. */
    fun parent(turn: TurnId): Job? {
        jobs.entries.removeAll { it.value.isCompleted }
        if (isClosed(turn)) return null
        return jobs[turn] ?: SupervisorJob(scope.coroutineContext[Job]).also { parent ->
            jobs[turn] = parent
        }
    }

    fun revoke(turn: TurnId) {
        closed += turn
        if (closed.size > MAX_CLOSED_TURNS) closed.remove(closed.first())
        jobs[turn]?.cancel()
    }

    /** False retains the cancelled parent for retry. Caller cancellation also preserves it and propagates. */
    suspend fun drain(turn: TurnId): Boolean {
        revoke(turn)
        val parent = jobs[turn] ?: return true
        val isFinished = withTimeoutOrNull(timeoutMillis) {
            parent.join()
            true
        } ?: false
        if (isFinished && jobs[turn] === parent) jobs.remove(turn)
        return isFinished
    }

    private companion object {
        const val MAX_CLOSED_TURNS = 32
        const val DRAIN_TIMEOUT = 5_000L
    }
}
