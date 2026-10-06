package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityIntent
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityKind
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityLimits
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityOutput
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityRejection
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityState
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundReservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal typealias BackgroundCapacityMachine =
    Machine<BackgroundCapacityState, BackgroundCapacityIntent, BackgroundCapacityOutput>

/** Caller cancellation removes pending acquisitions, while a returned reservation belongs to its native work. */
@SingleIn(ProfileScope::class)
@Inject
internal class ProfileBackgroundCapacity(
    private val machine: BackgroundCapacityMachine,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val journal: ActionJournal,
) {
    private val lock = Mutex()
    private var isRestored = false

    /** One barrier shared by every acquisition: existing native work is counted before any new admission. */
    suspend fun restore() = lock.withLock {
        if (isRestored) return@withLock
        val reservations = journal.readAll().filter {
            it.kind == "agent" && it.payload == null && (it.helper != null || it.parent == null || it.request == null)
        }.map { record ->
            BackgroundReservation(
                record.id,
                record.id,
                record.parent,
                if (record.parent == null) BackgroundCapacityKind.Helper else BackgroundCapacityKind.Scheduled,
            )
        }
        // Commit and remember the barrier together; caller cancellation must not replay already released records.
        withContext(NonCancellable) {
            check(machine.send(BackgroundCapacityIntent.Internal.Restore(reservations)) == SendResult.Accepted) {
                "Background capacity recovery unavailable"
            }
            isRestored = true
        }
    }

    /** The legacy scheduler path refuses immediately, retaining its per-session limit. */
    suspend fun tryAcquireScheduled(id: ActionId, parent: SessionRef): BackgroundCapacityRejection? = acquire(
        BackgroundReservation(id, id, parent, BackgroundCapacityKind.Scheduled),
    )

    /** Waits for both profile and workflow capacity; [id] must be new for this acquisition. */
    suspend fun acquireHelper(id: ActionId, owner: ActionId, parent: SessionRef?) {
        check(acquire(BackgroundReservation(id, owner, parent, BackgroundCapacityKind.Helper)) == null) {
            "Duplicate helper acquisition"
        }
    }

    /** Idempotent; the caller must have confirmed that the native work is terminal before releasing. */
    suspend fun release(id: ActionId) {
        lock.withLock { machine.send(BackgroundCapacityIntent.Public.Release(id)) }
    }

    private suspend fun acquire(request: BackgroundReservation): BackgroundCapacityRejection? {
        restore()
        var isSubmitted = false
        var isHandedOff = false
        try {
            val result = coroutineScope {
                val operation = currentCoroutineContext().job
                val registration = profile.onClose { operation.cancel(CancellationException("Profile closed")) }
                try {
                    currentCoroutineContext().ensureActive()
                    val rejection = lock.withLock {
                        val state = machine.state.value as BackgroundCapacityState.Ready
                        if ((state.active + state.queued).any { it.id == request.id }) {
                            return@withLock BackgroundCapacityRejection.Duplicate
                        }
                        // Cancellation can arrive after commit but before the machine acknowledges the send.
                        isSubmitted = true
                        check(machine.send(BackgroundCapacityIntent.Public.Acquire(request)) == SendResult.Accepted) {
                            "Background capacity unavailable"
                        }
                        val updated = machine.state.value as BackgroundCapacityState.Ready
                        when {
                            request in updated.active || request in updated.queued -> null

                            updated.active.size >= BackgroundCapacityLimits.PROFILE ->
                                BackgroundCapacityRejection.ProfileLimit

                            else -> BackgroundCapacityRejection.SessionLimit
                        }
                    }
                    if (rejection == null) {
                        machine.state.first { state ->
                            (state as BackgroundCapacityState.Ready).active.any { it.id == request.id }
                        }
                        currentCoroutineContext().ensureActive()
                    }
                    rejection
                } finally {
                    registration.dispose()
                }
            }
            // coroutineScope may reject its result on cancellation; transfer only after it actually returns.
            isHandedOff = result == null
            return result
        } finally {
            if (isSubmitted && !isHandedOff) withContext(NonCancellable) { release(request.id) }
        }
    }
}
