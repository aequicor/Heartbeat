package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDeferredException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
        beforeExecute: suspend () -> Unit = {},
        isExecutionEnabled: Flow<Boolean>? = null,
    ): RunOutcome {
        log.i { "Reserve profile-owned execution" }
        while (true) {
            if (isExecutionEnabled != null) {
                combine(busy, isExecutionEnabled) { occupied, enabled ->
                    !enabled || !waitForIdle || request.id !in occupied
                }.first { it }
            } else if (waitForIdle) {
                busy.first { request.id !in it }
            }
            val job = lock.withLock {
                // A disabled background request waits without reserving the chat or changing its visible state.
                if (isExecutionEnabled?.first() == false) throw ScheduledWakeDeferredException()
                check(!profile.isClosed) { "Profile is closed" }
                if (waitForIdle && request.id in busy.value) return@withLock null
                check(request.id !in busy.value) { "Session is busy" }
                host.startedRun(request.id, clock.now())
                busy.value += request.id
                profile.coroutineScope.async(start = CoroutineStart.LAZY) {
                    try {
                        beforeExecute()
                        host.executeRun(request)
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
                return job.await()
            }
        }
    }
}
