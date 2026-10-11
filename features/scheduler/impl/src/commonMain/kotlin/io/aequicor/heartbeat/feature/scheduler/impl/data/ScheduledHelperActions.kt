package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.EventNamespace
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Legacy scheduled helpers use the same lease as workflow helpers, adopting their scheduled reservation.
 * A timeout asks the lease to stop the exact attempt; neither its slot nor its result is released before proof.
 * Persisted identities allow that barrier to be retried after restart without submitting another prompt; records
 * from versions without those identities are reported as interrupted. Failed recovery and unconfirmed cleanup
 * retries back off; only an explicit recovery pass shortens their delay.
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
    private var hasRecovered = false

    suspend fun isHelper(session: SessionRef): Boolean = helpers.isHelper(session)

    /** Caller already journaled the action and reserved one scheduled slot; this method owns their cleanup. */
    suspend fun start(record: ActionRecord, request: SpawnRequest): String? {
        val entry = register(record)
        var lease: HelperLease? = null
        var helper: HelperId? = null
        var isWatching = false
        var isPromptAttempted = false
        try {
            lease = helpers.adoptScheduled(record.id, request.parent)
                ?: return "no chat host could start a helper agent"
            helper = helpers.create(lease, request.workspace, request.target, request.title, TrustLevel.Ask)
            val saved = record.copy(helper = helper)
            journal.add(saved)
            entry.record = saved
            isPromptAttempted = true
            launchWatch(entry, lease, submitFirst(record, request, helper))
            isWatching = true
            log.i { "action ${record.id}: supervised helper started" }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val owned = lease
            val created = helper
            if (!isPromptAttempted || owned == null || created == null) {
                log.w(e) { "action ${record.id}: helper startup failed" }
                return "helper could not be started"
            }
            // A failed prompt call may still have been accepted: report the action as started, never invite a
            // duplicate helper, and let the exact barrier, then the exact result if needed, finish the action.
            log.w(e) { "action ${record.id}: helper submission outcome unknown; settling its exact attempt" }
            launchWatch(entry, owned, failedPromptPayload(record, created, request.prompt.request))
            isWatching = true
            return null
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

    /** Returns the initial payload of a refused or timed-out first prompt, or null once it was accepted. */
    private suspend fun submitFirst(record: ActionRecord, request: SpawnRequest, helper: HelperId): String? {
        val submission = withTimeoutOrNull(remaining(record)) {
            helpers.prompt(
                helper,
                HelperPrompt(
                    request.prompt.request,
                    request.prompt.visible,
                    handoff = record.initiator?.let { HelperHandoff(initiator = it) },
                    directives = listOf(request.prompt.directive),
                ),
            )
        }
        return when (submission) {
            is HelperSubmission.Accepted -> null
            is HelperSubmission.NotSubmitted -> "status: failed (helper was not submitted)"
            null -> "status: timed out after $MAX_HELPER_TIME"
        }
    }

    /**
     * After a failed prompt, the exact cancellation barrier decides: a request proven not submitted finishes at
     * once instead of holding its slot until the timeout; a terminal one finishes with its result; otherwise
     * (unconfirmed or an unavailable barrier) the exact result is observed. Null means observe.
     */
    private suspend fun failedPromptPayload(record: ActionRecord, helper: HelperId, request: RequestId): String? = try {
        when (val barrier = helpers.cancel(helper, request)) {
            is HelperCancellation.NotSubmitted -> "status: failed (helper was not submitted)"
            is HelperCancellation.Terminal -> barrier.result.payload()
            is HelperCancellation.Unconfirmed -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "action ${record.id}: helper barrier after a failed prompt unavailable; observing its result" }
        null
    }

    /**
     * Retries existing cleanup and reacquires reservations for durable helpers before reconciling their attempt.
     * The first pass after start, or an [isExplicit] one, retries supervised helpers at once; the passes every
     * reservation runs only wake their waits for native proof and never cut a failure backoff short.
     */
    suspend fun recover(isExplicit: Boolean = false) = recoveryLock.withLock {
        capacity.restore()
        val isImmediate = isExplicit || !hasRecovered
        hasRecovered = true
        lock.withLock { running.values.toList() }.forEach { if (isImmediate) it.retryNow() else it.wake() }
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
            if (record.parent == null || record.request == null) {
                // Versions before helper leases kept no native identity or slot; report the run as interrupted.
                log.i { "action ${record.id}: legacy helper interrupted by restart" }
                results.complete(record, "status: interrupted (the app closed before the agent finished)")
                return
            }
            if (record.helper == null) {
                // This version journals the empty helper before any prompt; no identity means no submission.
                results.complete(record, "status: interrupted (helper was not submitted)")
                return
            }
            var lease: HelperLease? = null
            while (lease == null) {
                lease = restoredLease(entry)
                if (lease == null) entry.backOff()
            }
            entry.recovered().takeIf { it > 0 }?.let { failures ->
                log.i { "action ${record.id}: helper reconciliation recovered after $failures failures" }
            }
            val payload = exactResult(entry)?.payload()
                ?: "status: interrupted (helper termination confirmed after restart)"
            settle(entry, lease, payload)
        } finally {
            withContext(NonCancellable) { retire(entry) }
        }
    }

    private suspend fun restoredLease(entry: Entry): HelperLease? {
        // A host may cancel its own metadata read. Keep the profile-owned supervisor alive for a later retry.
        val attempt = scope.coroutineScope.async { adoptOrNull(entry) }
        attempt.join()
        if (attempt.isCancelled) {
            val failures = entry.countFailure()
            if (isReported(failures)) {
                log.w { "action ${entry.record.id}: helper reconciliation wait cancelled (failures=$failures)" }
            } else {
                log.v { "action ${entry.record.id}: helper reconciliation wait cancelled again (failures=$failures)" }
            }
            return null
        }
        return attempt.await()
    }

    private suspend fun adoptOrNull(entry: Entry): HelperLease? = try {
        val record = entry.record
        helpers.adoptScheduled(record.id, checkNotNull(record.parent), checkNotNull(record.helper), record.request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The first failure and a periodic summary are warnings; repeats in between stay on trace.
        val failures = entry.countFailure()
        if (isReported(failures)) {
            log.w(e) { "action ${entry.record.id}: helper reconciliation unavailable (failures=$failures)" }
        } else {
            log.v { "action ${entry.record.id}: helper reconciliation still unavailable (failures=$failures)" }
        }
        null
    }

    private fun isReported(failures: Int) = failures == 1 || failures % FAILURE_SUMMARY_EVERY == 0

    /** A result confirmed before restart is delivered as is; only its absence makes the run interrupted. */
    private suspend fun exactResult(entry: Entry): HelperResult? = try {
        helpers.result(checkNotNull(entry.record.helper), checkNotNull(entry.record.request))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "action ${entry.record.id}: restored helper result unavailable; reconciling as interrupted" }
        null
    }

    private fun launchWatch(entry: Entry, lease: HelperLease, payload: String?) {
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

    /** Unconfirmed cleanup is retried with the same backoff and warning budget as recovery adoption. */
    private suspend fun settle(entry: Entry, lease: HelperLease, payload: String) {
        while (!releaseConfirmed(entry, lease)) entry.backOff()
        entry.recovered().takeIf { it > 0 }?.let { failures ->
            log.i { "action ${entry.record.id}: helper cleanup confirmed after $failures unconfirmed attempts" }
        }
        results.complete(entry.record, payload)
    }

    private suspend fun releaseConfirmed(entry: Entry, lease: HelperLease): Boolean {
        // A host can cancel its own cleanup wait. Joining observes that outcome without cancelling this supervisor.
        val attempt = scope.coroutineScope.async { lease.release() }
        attempt.join()
        val outcome = if (attempt.isCancelled) "wait cancelled" else null
        if (outcome == null && attempt.await() == HelperReleaseResult.Released) return true
        val failures = entry.countFailure()
        val reason = outcome ?: "unconfirmed"
        if (isReported(failures)) {
            log.w { "action ${entry.record.id}: helper cleanup $reason; slot retained (failures=$failures)" }
        } else {
            log.v { "action ${entry.record.id}: helper cleanup still $reason (failures=$failures)" }
        }
        return false
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
        private val retry = Channel<Unit>(Channel.CONFLATED)
        var watcher: kotlinx.coroutines.Job? = null
        private var failures = 0

        /** A session event: shortens waits for native proof, but never a failure backoff. */
        fun wake() {
            signal.trySend(Unit)
        }

        /** An explicit recovery pass retries immediately, including after failures. */
        fun retryNow() {
            retry.trySend(Unit)
            signal.trySend(Unit)
        }

        suspend fun awaitReconciliation() {
            // Events reduce latency, but native/hosted cleanup can finish without another session event.
            // Expiry only retries authoritative proof; it never completes work or releases capacity.
            withTimeoutOrNull(RECONCILIATION_INTERVAL) { signal.receive() }
        }

        /** Consecutive failed recovery attempts, reset once reconciliation succeeds. */
        fun countFailure(): Int = ++failures

        /** Exponential delay after a failed attempt; only an explicit recovery pass shortens it. */
        suspend fun backOff() {
            val exponent = (failures - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
            withTimeoutOrNull((RECONCILIATION_INTERVAL * (1 shl exponent)).coerceAtMost(MAX_BACKOFF)) {
                retry.receive()
            }
            signal.tryReceive()
        }

        /** Returns how many failed attempts preceded this success. */
        fun recovered(): Int = failures.also { failures = 0 }
    }

    private companion object {
        val MAX_HELPER_TIME = 6.hours
        val RECONCILIATION_INTERVAL = 5.seconds
        val MAX_BACKOFF = 5.minutes
        const val MAX_BACKOFF_EXPONENT = 6
        const val FAILURE_SUMMARY_EVERY = 20
    }
}

private fun HelperResult.payload(): String =
    "status: ${if (outcome == HelperOutcome.Completed) "finished" else outcome.name.lowercase()}\nfinal message:\n$answer"
