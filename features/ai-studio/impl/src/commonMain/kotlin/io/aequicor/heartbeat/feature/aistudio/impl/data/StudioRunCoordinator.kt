package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDeferredException
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDroppedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/** Persistence and screen projection around a profile-owned request. */
internal interface StudioRunHost {
    suspend fun startedRun(id: String, at: Instant)
    suspend fun executeRun(request: StudioTurnRequest): RunOutcome
    suspend fun finishedRun(id: String)
}

/**
 * Reserves a chat before action handoff, independently of the lifetime of the UI caller. One per profile: the studio
 * runtime and scheduled runs share its busy set.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioRunCoordinator(
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val clock: Clock,
) {
    private val log = Log.tag("StudioRunCoordinator")
    private val lock = Mutex()
    private val busy = MutableStateFlow<Set<String>>(emptySet())

    suspend fun run(
        host: StudioRunHost,
        request: StudioTurnRequest,
        waitForIdle: Boolean = false,
        cancelBeforeSubmission: Boolean = false,
        beforeExecute: suspend () -> Unit = {},
        onCancelledBeforeSubmission: suspend () -> Unit = {},
        admission: Flow<ScheduledWakeAdmission>? = null,
    ): RunOutcome {
        log.i { "Reserve profile-owned execution" }
        val submission = if (cancelBeforeSubmission || admission != null) {
            StudioRunSubmission(admission)
        } else {
            null
        }
        val reserved = submission?.let { request.copy(submission = it) } ?: request
        while (true) {
            awaitAdmission(request.id, waitForIdle, admission)
            val job = lock.withLock {
                // A disabled background request waits without reserving the chat or changing its visible state.
                when (admission?.first()) {
                    ScheduledWakeAdmission.Defer -> throw ScheduledWakeDeferredException()
                    ScheduledWakeAdmission.Drop -> throw ScheduledWakeDroppedException()
                    ScheduledWakeAdmission.Allow, null -> Unit
                }
                check(!profile.isClosed) { "Profile is closed" }
                if (waitForIdle && request.id in busy.value) return@withLock null
                check(request.id !in busy.value) { "Session is busy" }
                host.startedRun(request.id, clock.now())
                busy.value += request.id
                // Install cleanup before an admission/caller cancellation can revoke the reserved request.
                profile.coroutineScope.async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        beforeExecute()
                        host.executeRun(reserved)
                    } finally {
                        // An owner/source may cancel preparation itself. Settle its receipt before leaving the job.
                        submission?.cancel()
                        withContext(NonCancellable) {
                            finishRun(host, request.id, submission, onCancelledBeforeSubmission)
                        }
                    }
                }
            }
            if (job != null) return awaitRun(job, submission)
        }
    }

    private suspend fun awaitAdmission(id: String, waitForIdle: Boolean, admission: Flow<ScheduledWakeAdmission>?) {
        if (admission != null) {
            combine(busy, admission) { occupied, decision ->
                decision != ScheduledWakeAdmission.Allow || !waitForIdle || id !in occupied
            }.first { it }
        } else if (waitForIdle) {
            busy.first { id !in it }
        }
    }

    private suspend fun finishRun(
        host: StudioRunHost,
        id: String,
        submission: StudioRunSubmission?,
        onCancelledBeforeSubmission: suspend () -> Unit,
    ) {
        try {
            if (submission?.isCancelled == true) onCancelledBeforeSubmission()
        } finally {
            withContext(NonCancellable) {
                try {
                    host.finishedRun(id)
                } finally {
                    withContext(NonCancellable) {
                        lock.withLock { busy.value -= id }
                        log.v { "Released the chat execution reservation" }
                    }
                }
            }
        }
    }

    private suspend fun awaitRun(job: Deferred<RunOutcome>, submission: StudioRunSubmission?): RunOutcome =
        coroutineScope {
            val caller = currentCoroutineContext()
            val gate = launch(start = CoroutineStart.UNDISPATCHED) {
                submission?.awaitRevocation()?.let { job.cancel(it) }
            }
            try {
                job.await()
            } catch (e: StudioPreparationDropped) {
                caller.ensureActive()
                log.v { "Scheduled preparation dropped before native submission" }
                throw ScheduledWakeDroppedException().also { it.addSuppressed(e) }
            } catch (e: StudioPreparationDeferred) {
                // This cancellation belongs to the admission gate; cancellation of the caller still propagates.
                caller.ensureActive()
                log.v { "Scheduled preparation deferred before native submission" }
                throw ScheduledWakeDeferredException().also { it.addSuppressed(e) }
            } finally {
                gate.cancel()
                if (!caller.isActive && submission?.cancel() == true) {
                    log.i { "Cancel scheduled preparation before native submission" }
                    job.cancel()
                }
            }
        }
}

/**
 * A scheduled caller may revoke preparation until native submission begins. The atomic handoff preserves profile
 * ownership when cancellation races a send: once submitted, even an unknown native outcome must be reconciled.
 * The owning feature may defer or drop Preparing, but cannot revoke Submitted. Begin checks admission again
 * after preparation.
 */
internal class StudioRunSubmission(private val admission: Flow<ScheduledWakeAdmission>? = null) {
    private val log = Log.tag("StudioRunSubmission")
    private val phase = MutableStateFlow(Phase.Preparing)

    val isCancelled: Boolean get() = phase.value in setOf(Phase.Cancelled, Phase.Deferred, Phase.Dropped)

    suspend fun begin() {
        admission?.first()?.let(::revoke)
        if (!phase.compareAndSet(Phase.Preparing, Phase.Submitted)) {
            throw revocation() ?: CancellationException("Scheduled preparation was cancelled before native submission")
        }
        log.v { "Native submission took ownership of the scheduled run" }
    }

    fun cancel(): Boolean {
        val isCancelled = phase.compareAndSet(Phase.Preparing, Phase.Cancelled)
        log.v { "Scheduled preparation cancellation accepted=$isCancelled" }
        return isCancelled
    }

    suspend fun awaitRevocation(): CancellationException? {
        val decisions = admission ?: return null
        val decision = combine(phase, decisions) { current, next -> current to next }
            .first { (current, next) -> current != Phase.Preparing || next != ScheduledWakeAdmission.Allow }.second
        revoke(decision)
        return revocation()
    }

    private fun revoke(decision: ScheduledWakeAdmission) {
        val next = when (decision) {
            ScheduledWakeAdmission.Allow -> return
            ScheduledWakeAdmission.Defer -> Phase.Deferred
            ScheduledWakeAdmission.Drop -> Phase.Dropped
        }
        val isRevoked = phase.compareAndSet(Phase.Preparing, next)
        log.v { "Scheduled preparation revocation accepted=$isRevoked" }
    }

    private fun revocation(): CancellationException? = when (phase.value) {
        Phase.Deferred -> StudioPreparationDeferred()
        Phase.Dropped -> StudioPreparationDropped()
        Phase.Preparing, Phase.Submitted, Phase.Cancelled -> null
    }

    private enum class Phase { Preparing, Submitted, Cancelled, Deferred, Dropped }
}

/** Internal admission cancellation, translated to the scheduler's retriable deferral after cleanup. */
private class StudioPreparationDeferred : CancellationException("Scheduled preparation deferred")

/** Internal owner cancellation, translated to permanent rejection after cleanup. */
private class StudioPreparationDropped : CancellationException("Scheduled preparation dropped")
