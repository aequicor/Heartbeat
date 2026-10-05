package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
    ): RunOutcome {
        log.i { "Reserve profile-owned execution" }
        val submission = if (cancelBeforeSubmission) StudioRunSubmission() else null
        val reserved = submission?.let { request.copy(submission = it) } ?: request
        while (true) {
            if (waitForIdle) busy.first { request.id !in it }
            val job = lock.withLock {
                check(!profile.isClosed) { "Profile is closed" }
                if (waitForIdle && request.id in busy.value) return@withLock null
                check(request.id !in busy.value) { "Session is busy" }
                host.startedRun(request.id, clock.now())
                busy.value += request.id
                profile.coroutineScope.async(start = CoroutineStart.LAZY) {
                    try {
                        beforeExecute()
                        host.executeRun(reserved)
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                host.finishedRun(request.id)
                            } finally {
                                lock.withLock { busy.value -= request.id }
                            }
                        }
                    }
                }
            }
            if (job != null) {
                job.start()
                return awaitRun(job, submission)
            }
        }
    }

    private suspend fun awaitRun(job: Deferred<RunOutcome>, submission: StudioRunSubmission?): RunOutcome {
        val caller = currentCoroutineContext()
        return try {
            job.await()
        } finally {
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
 */
internal class StudioRunSubmission {
    private val log = Log.tag("StudioRunSubmission")
    private val phase = MutableStateFlow(Phase.Preparing)

    val isCancelled: Boolean get() = phase.value == Phase.Cancelled

    fun begin() {
        if (!phase.compareAndSet(Phase.Preparing, Phase.Submitted)) {
            throw CancellationException("Scheduled preparation was cancelled before native submission")
        }
        log.v { "Native submission took ownership of the scheduled run" }
    }

    fun cancel(): Boolean {
        val isCancelled = phase.compareAndSet(Phase.Preparing, Phase.Cancelled)
        log.v { "Scheduled preparation cancellation accepted=$isCancelled" }
        return isCancelled
    }

    private enum class Phase { Preparing, Submitted, Cancelled }
}
