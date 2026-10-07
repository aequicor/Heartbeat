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
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoveryRecord
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoverySource
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
    private val graphs: GraphCapacityRecords = GraphCapacityRecords { emptyList() },
    private val helpers: Lazy<Set<HelperCapacityRecoverySource>> = lazyOf(emptySet()),
) {
    private val lock = Mutex()
    private var isRestored = false
    private var recoveredHelpers = emptyMap<ActionId, HelperCapacityRecoveryRecord>()
    private val helperClaims = mutableSetOf<ActionId>()
    val changes get() = machine.state

    /** One barrier shared by every acquisition: existing native work is counted before any new admission. */
    suspend fun restore() = lock.withLock {
        if (isRestored) return@withLock
        val helperRecords = helpers.value.flatMap { it.reservations() }
        check(helperRecords.map { it.reservation }.distinct().size == helperRecords.size) {
            "Duplicate helper recovery reservation"
        }
        val helperIds = helperRecords.mapNotNull { it.helper }
        check(helperIds.distinct().size == helperIds.size) { "Duplicate recovered helper identity" }
        val reservations = helperRecords.map {
            BackgroundReservation(it.reservation, it.owner, it.parent, BackgroundCapacityKind.Helper)
        } + graphs.reservations() + journal.readAll().filter {
            it.kind == "agent" && it.payload == null && (it.helper != null || it.parent == null || it.request == null)
        }.map { record ->
            BackgroundReservation(
                record.id,
                record.id,
                record.parent,
                if (record.parent == null) BackgroundCapacityKind.Helper else BackgroundCapacityKind.Scheduled,
            )
        }
        check(reservations.map { it.id }.distinct().size == reservations.size) {
            "Conflicting background recovery reservation"
        }
        // Commit and remember the barrier together; caller cancellation must not replay already released records.
        withContext(NonCancellable) {
            check(machine.send(BackgroundCapacityIntent.Internal.Restore(reservations)) == SendResult.Accepted) {
                "Background capacity recovery unavailable"
            }
            recoveredHelpers = helperRecords.associateBy { it.reservation }
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

    /** Claims exactly one restored slot without admitting new work; null means a genuinely new id. */
    suspend fun claimRestoredHelper(
        id: ActionId,
        owner: ActionId,
        parent: SessionRef?,
        helper: HelperId?,
    ): HelperCapacityRecoveryRecord? {
        restore()
        return lock.withLock {
            currentCoroutineContext().ensureActive()
            val active = (machine.state.value as BackgroundCapacityState.Ready).active
            val record = recoveredHelpers[id]
            if (record == null) {
                val isAlreadyCounted = recoveredHelpers.values.any { old ->
                    helper != null && old.helper == helper && active.any { it.id == old.reservation }
                }
                check(!isAlreadyCounted) { "Recovered helper requires its original reservation identity" }
                return@withLock null
            }
            check(record.owner == owner && record.parent == parent && record.helper == helper) {
                "Restored helper reservation ownership does not match"
            }
            val expected = BackgroundReservation(id, owner, parent, BackgroundCapacityKind.Helper)
            check((machine.state.value as BackgroundCapacityState.Ready).active.contains(expected)) {
                "Restored helper reservation was already released"
            }
            check(helperClaims.add(id)) { "Restored helper reservation already has a lease" }
            record
        }
    }

    /** A failed adoption returns only its temporary claim, never capacity owned by unresolved work. */
    suspend fun returnHelperClaim(id: ActionId) {
        lock.withLock { helperClaims.remove(id) }
    }

    /** Restored native work counts even when it exceeds the current admission quota. */
    suspend fun restoreScheduled(id: ActionId, parent: SessionRef) {
        restore()
        lock.withLock {
            val reservation = BackgroundReservation(id, id, parent, BackgroundCapacityKind.Scheduled)
            machine.send(BackgroundCapacityIntent.Internal.Restore(listOf(reservation)))
        }
    }

    /** Transfer never releases capacity to another waiter between old and new recovery attempts. */
    suspend fun transferScheduled(previous: ActionId, next: ActionId, parent: SessionRef): Boolean {
        restore()
        return lock.withLock {
            val ready = machine.state.value as BackgroundCapacityState.Ready
            if (ready.active.none { it.id == previous && it.parent == parent } ||
                (ready.active + ready.queued).any { it.id == next }
            ) {
                return@withLock false
            }
            withContext(NonCancellable) {
                val reservation = BackgroundReservation(next, next, parent, BackgroundCapacityKind.Scheduled)
                machine.send(BackgroundCapacityIntent.Internal.Restore(listOf(reservation)))
                machine.send(BackgroundCapacityIntent.Public.Release(previous))
            }
            true
        }
    }

    /** Idempotent; the caller must have confirmed that the native work is terminal before releasing. */
    suspend fun release(id: ActionId) {
        lock.withLock {
            machine.send(BackgroundCapacityIntent.Public.Release(id))
            helperClaims.remove(id)
        }
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
