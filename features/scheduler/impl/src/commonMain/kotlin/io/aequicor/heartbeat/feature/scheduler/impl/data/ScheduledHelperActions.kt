package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.EventNamespace
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperLease
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperSubmission
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Legacy scheduled helpers use the same lease as workflow helpers, adopting their scheduled reservation.
 * A timeout asks the lease to stop the exact attempt; neither its slot nor its result is released before proof.
 * Persisted identities allow that barrier to be retried after restart without submitting another prompt.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class ScheduledHelperActions(
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    private val helpers: ScheduledHelperLeases,
    private val capacity: ProfileBackgroundCapacity,
    private val journal: ActionJournal,
    private val results: ActionResults,
    private val bus: SchedulerBus,
    private val clock: Clock,
) {
    private val log = Log.tag("ScheduledHelperActions")
    private val recoveryLock = Mutex()
    private val lock = Mutex()
    private val running = mutableMapOf<ActionId, Entry>()

    suspend fun isHelper(session: SessionRef): Boolean = helpers.isHelper(session)

    /** Caller already journaled the action and reserved one scheduled slot; this method owns their cleanup. */
    suspend fun start(record: ActionRecord, request: SpawnRequest): String? {
        val entry = register(record)
        var lease: HelperLease? = null
        var isWatching = false
        try {
            lease = helpers.adoptScheduled(record.id, request.parent)
                ?: return "no chat host could start a helper agent"
            val helper = helpers.create(lease, request.workspace, request.target, request.title, TrustLevel.Ask)
            val saved = record.copy(helper = helper)
            journal.add(saved)
            entry.record = saved
            val submission = withTimeoutOrNull(remaining(record)) {
                helpers.prompt(
                    helper,
                    HelperPrompt(
                        request.prompt.request,
                        request.prompt.visible,
                        handoff = record.initiator?.let { HelperHandoff(initiator = it) },
                    ),
                )
            }
            val payload = when (submission) {
                is HelperSubmission.Accepted -> null
                is HelperSubmission.NotSubmitted -> "status: failed (helper was not submitted)"
                null -> "status: timed out after $MAX_HELPER_TIME"
            }
            val owned = lease
            scope.coroutineScope.launch { watch(entry, owned, payload) }
            isWatching = true
            log.i { "action ${record.id}: supervised helper started" }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "action ${record.id}: helper startup failed" }
            return "helper could not be started"
        } finally {
            if (!isWatching) {
                val owned = lease
                if (owned == null) {
                    withContext(NonCancellable) {
                        recoveryLock.withLock {
                            results.abandon(record.id)
                            capacity.release(record.id)
                            unregister(entry)
                        }
                    }
                } else {
                    launchWatch(entry, owned, "status: failed (helper startup interrupted)")
                }
            }
        }
    }

    /** Retries existing cleanup and reacquires reservations for durable helpers before reconciling their attempt. */
    suspend fun recover() = recoveryLock.withLock {
        capacity.restore()
        lock.withLock { running.values.toList() }.forEach { it.wake() }
        journal.readAll().filter { it.kind == "agent" && it.payload == null }.forEach { record ->
            if (!results.isOwned(record.id)) {
                results.begin(record)
                withContext(NonCancellable) {
                    val entry = register(record)
                    scope.coroutineScope.launch { restore(entry) }
                }
            }
        }
    }

    private suspend fun restore(entry: Entry) {
        val record = entry.record
        try {
            if (record.helper == null && record.parent != null && record.request != null) {
                // This version journals the empty helper before any prompt; no identity means no submission.
                results.complete(record, "status: interrupted (helper was not submitted)")
                return
            }
            if (record.parent == null || record.helper == null || record.request == null) {
                // Old records have no native identity: absence of proof cannot become a completion event.
                log.w { "action ${record.id}: legacy helper identity unavailable; reservation retained" }
                awaitCancellation()
            }
            var lease: HelperLease? = null
            while (lease == null) {
                lease = restoredLease(entry)
                if (lease == null) entry.awaitReconciliation()
            }
            settle(entry, lease, "status: interrupted (helper termination confirmed after restart)")
        } finally {
            withContext(NonCancellable) { retire(entry) }
        }
    }

    private suspend fun restoredLease(entry: Entry): HelperLease? {
        // A host may cancel its own metadata read. Keep the profile-owned supervisor alive for a later retry.
        val attempt = scope.coroutineScope.async { adoptOrNull(entry.record) }
        attempt.join()
        return if (attempt.isCancelled) null else attempt.await()
    }

    private suspend fun adoptOrNull(record: ActionRecord): HelperLease? = try {
        helpers.adoptScheduled(record.id, checkNotNull(record.parent), checkNotNull(record.helper), record.request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "action ${record.id}: helper reconciliation unavailable; reservation retained" }
        null
    }

    private fun launchWatch(entry: Entry, lease: HelperLease, payload: String) {
        scope.coroutineScope.launch { watch(entry, lease, payload) }
    }

    private suspend fun watch(entry: Entry, lease: HelperLease, initialPayload: String?) {
        try {
            settle(entry, lease, initialPayload ?: observedPayload(entry))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "action ${entry.record.id}: result delivery failed, retained for recovery" }
        } finally {
            withContext(NonCancellable) { retire(entry) }
        }
    }

    private suspend fun observedPayload(entry: Entry): String {
        val observation = scope.coroutineScope.async { observe(entry) }
        observation.join()
        if (observation.isCancelled) {
            log.w { "action ${entry.record.id}: result wait cancelled; reconciling before completion" }
            return "status: interrupted (helper result wait cancelled)"
        }
        return observation.await()
    }

    private suspend fun observe(entry: Entry): String = try {
        withTimeoutOrNull(remaining(entry.record)) {
            var result: HelperResult? = null
            while (result == null) {
                result = helpers.result(checkNotNull(entry.record.helper), checkNotNull(entry.record.request))
                if (result == null) entry.awaitReconciliation()
            }
            result.payload()
        } ?: "status: timed out after $MAX_HELPER_TIME"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "action ${entry.record.id}: helper observation failed; reconciling before completion" }
        "status: failed (helper observation unavailable)"
    }

    private suspend fun settle(entry: Entry, lease: HelperLease, payload: String) {
        while (!releaseConfirmed(lease)) entry.awaitReconciliation()
        results.complete(entry.record, payload)
    }

    private suspend fun releaseConfirmed(lease: HelperLease): Boolean {
        // A host can cancel its own cleanup wait. Joining observes that outcome without cancelling this supervisor.
        val attempt = scope.coroutineScope.async { lease.release() }
        attempt.join()
        if (attempt.isCancelled) {
            log.w { "helper cleanup wait cancelled; reservation retained for reconciliation" }
            return false
        }
        return attempt.await() == HelperReleaseResult.Released
    }

    private suspend fun register(record: ActionRecord): Entry {
        val entry = Entry(record)
        lock.withLock {
            check(record.id !in running) { "Helper action already supervised" }
            running[record.id] = entry
        }
        entry.watcher = scope.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.events.collect { if (it.key.namespace == EventNamespace.Session) entry.wake() }
        }
        return entry
    }

    private suspend fun retire(entry: Entry) = withContext(NonCancellable) {
        recoveryLock.withLock {
            unregister(entry)
            results.releaseOwnership(entry.record.id)
        }
    }

    private suspend fun unregister(entry: Entry) {
        entry.watcher?.cancel()
        lock.withLock { if (running[entry.record.id] === entry) running.remove(entry.record.id) }
    }

    private fun remaining(record: ActionRecord) = MAX_HELPER_TIME - (clock.now() - record.startedAt)

    private class Entry(var record: ActionRecord) {
        val signal = Channel<Unit>(Channel.CONFLATED)
        var watcher: kotlinx.coroutines.Job? = null

        fun wake() {
            signal.trySend(Unit)
        }

        suspend fun awaitReconciliation() {
            // Events reduce latency, but native/hosted cleanup can finish without another session event.
            // Expiry only retries authoritative proof; it never completes work or releases capacity.
            withTimeoutOrNull(RECONCILIATION_INTERVAL) { signal.receive() }
        }
    }

    private companion object {
        val MAX_HELPER_TIME = 6.hours
        val RECONCILIATION_INTERVAL = 5.seconds
    }
}

private fun HelperResult.payload(): String =
    "status: ${if (outcome == HelperOutcome.Completed) "finished" else outcome.name.lowercase()}\nfinal message:\n$answer"
