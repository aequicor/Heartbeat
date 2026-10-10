package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHelper
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperSubmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Profile-owned script helpers. Returning or cancelling the script's wait never transfers a lease to script code.
 * Initialization installs a complete old-profile snapshot before new producers start; every restored operation
 * is cleanup-only. A closing producer is cancelled and joined before release and deletion, including all journal
 * writes. Result monitors are independent siblings: a blocked read cannot delay the native cancellation barrier.
 */
internal class HarnessSpawnOperations(
    private val scope: CoroutineScope,
    private val journal: HarnessSpawnJournal,
    private val helpers: Lazy<HelperAgents>,
    private val bindings: HarnessHelperBindings,
) : HarnessSpawnLifecycle {
    private val log = Log.tag("HarnessSpawns")
    private val initialization = Mutex()
    private val registry = Mutex()
    private val entries = mutableMapOf<ActionId, HarnessSpawnEntry>()
    private val harnessFences = mutableMapOf<HarnessId, Long>()
    private val itemFences = mutableMapOf<Pair<HarnessId, ItemId>, Long>()
    private var isInitialized = false

    /** Called even while the feature is off. Failure blocks new spawns and may be retried without a partial load. */
    suspend fun initialize() = initialization.withLock {
        if (isInitialized) return@withLock
        check(scope.isActive) { "Profile is closed" }
        val records = journal.pending()
        check(records.map { it.reservation }.distinct().size == records.size) { "Duplicate spawn reservation" }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val restored = registry.withLock {
                records.map { record ->
                    HarnessSpawnEntry(record, isRestored = true).also { entries[record.reservation] = it }
                }
            }
            // Install every entry and register all cleanup before a cancelled initializer can return.
            restored.forEach { requestCleanup(it) }
            isInitialized = true
        }
    }

    /** [submission] contains only host-captured identity/authority; no author callbacks execute in this owner. */
    suspend fun spawn(submission: HarnessSpawnSubmission): ScriptHelper {
        initialize()
        check(submission.record.helper == null && submission.prompt.request == submission.record.request) {
            "Invalid new spawn identity"
        }
        check(submission.isAdmitted()) { "Harness helper admission was revoked" }
        currentCoroutineContext().ensureActive()
        val entry = registry.withLock {
            check(scope.isActive && !isFenced(submission.record.owner)) { "Harness helper admission was revoked" }
            check(submission.record.reservation !in entries) { "Spawn reservation already exists" }
            HarnessSpawnEntry(submission.record, isRestored = false).also { entry ->
                entry.producer = scope.launch(start = CoroutineStart.LAZY) { produce(entry, submission) }
                entry.producer?.invokeOnCompletion { error ->
                    if (error != null) entry.result.completeExceptionally(error)
                }
                entries[entry.record.reservation] = entry
            }
        }
        entry.producer?.start()
        return entry.result.await()
    }

    /** Installs generation fences before any IO; false is an observation, never permission to discard evidence. */
    override suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean {
        val selected = registry.withLock {
            effect.harnesses.forEach { harnessFences.raise(it, effect.generation) }
            effect.items.forEach { itemFences.raise(it.harness.id to it.item.id, it.generation) }
            entries.values.filter { effect.covers(it) }
        }
        selected.forEach { requestCleanup(it) }
        initialize()
        return registry.withLock { entries.values.none { effect.covers(it) } }
    }

    /** Exact instance retirement also handles successful code replacement and failure-driven unload. */
    suspend fun retire(owner: HarnessSpawnOwner): Boolean {
        val selected = registry.withLock {
            itemFences.raise(owner.harness to owner.item, owner.generation)
            entries.values.filter { it.isCoveredBy(owner) }
        }
        selected.forEach { requestCleanup(it) }
        initialize()
        return registry.withLock { entries.values.none { it.isCoveredBy(owner) } }
    }

    /** Initial snapshot is always required, but old cleanup does not prevent admission to remaining shared slots. */
    suspend fun isQuiescent(harness: HarnessId? = null): Boolean {
        initialize()
        return registry.withLock { entries.values.none { harness == null || it.record.owner.harness == harness } }
    }

    private suspend fun produce(entry: HarnessSpawnEntry, submission: HarnessSpawnSubmission) {
        var isMonitoring = false
        try {
            admit(entry, submission)
            entry.lease = helpers.value.acquire(
                entry.record.action,
                entry.record.parent,
                reservation = entry.record.reservation,
            )
            entry.hasJournalWriteStarted = true
            withContext(NonCancellable) { journal.recordGranted(entry.record) }
            admit(entry, submission)
            val helper = helpers.value.create(
                entry.leaseRequired(),
                submission.workspace,
                null,
                submission.title,
                TrustLevel.Ask,
            )
            withContext(NonCancellable) {
                entry.record = journal.bindHelper(entry.record.reservation, helper)
                bindings.bind(
                    HarnessHelperBinding(
                        helper,
                        entry.record.action,
                        entry.record.owner.harness,
                        entry.record.attachRequest,
                    ),
                )
            }
            admit(entry, submission)
            val accepted = helpers.value.prompt(helper, submission.prompt)
            check(accepted.request == entry.record.request && accepted is HelperSubmission.Accepted) {
                "Helper prompt was not accepted"
            }
            registry.withLock {
                check(!entry.isClosing) { "Harness helper was revoked" }
                entry.monitor = scope.launch(start = CoroutineStart.LAZY) { monitor(entry, helper) }
                isMonitoring = true
            }
            entry.monitor?.start()
            entry.result.complete(ScriptHelper(helper, accepted.request, accepted.session))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(harnessScriptFailure(error)) { "Helper producer did not complete" }
            entry.result.completeExceptionally(IllegalStateException("Harness helper submission failed"))
        } finally {
            if (!isMonitoring) withContext(NonCancellable) { requestCleanup(entry) }
        }
    }

    private suspend fun admit(entry: HarnessSpawnEntry, submission: HarnessSpawnSubmission) {
        currentCoroutineContext().ensureActive()
        check(submission.isAdmitted()) { "Harness helper admission was revoked" }
        registry.withLock { check(!entry.isClosing && !isFenced(entry.record.owner)) { "Harness helper was revoked" } }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun monitor(entry: HarnessSpawnEntry, helper: HelperId) {
        try {
            while (currentCoroutineContext().isActive) {
                val result = helpers.value.result(helper, entry.record.request)
                if (result != null) {
                    check(result.request == entry.record.request) { "Helper returned another request's result" }
                    break
                }
                delay(2.seconds)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(harnessScriptFailure(error)) { "Helper result inspection failed" }
        } finally {
            withContext(NonCancellable) { requestCleanup(entry) }
        }
    }

    private suspend fun requestCleanup(entry: HarnessSpawnEntry) {
        val cleanup = registry.withLock {
            if (entries[entry.record.reservation] !== entry) return@withLock null
            entry.isClosing = true
            entry.cleanup?.takeUnless { it.isCompleted } ?: cleanupJob(entry).also { entry.cleanup = it }
        } ?: return
        entry.producer?.cancel()
        entry.monitor?.cancel()
        cleanup.start()
    }

    /** A dependency can cancel its own call without closing the profile; that attempt propagates cancellation. */
    private fun cleanupJob(entry: HarnessSpawnEntry) = scope.launch(start = CoroutineStart.LAZY) {
        cleanup(entry)
    }.also { job ->
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException && scope.isActive) {
                scope.launch {
                    delay(5.seconds)
                    requestCleanup(entry)
                }
            }
        }
    }

    private suspend fun cleanup(entry: HarnessSpawnEntry) {
        // A late Room write cannot resurrect a row after settle. A blocked monitor owns no journal writes.
        entry.producer?.join()
        while (currentCoroutineContext().isActive) {
            if (tryCleanup(entry)) {
                registry.withLock { entries.remove(entry.record.reservation) }
                return
            }
            delay(5.seconds)
        }
    }

    private suspend fun tryCleanup(entry: HarnessSpawnEntry): Boolean = try {
        if (entry.isRestored && entry.lease == null) {
            entry.lease = helpers.value.acquire(
                entry.record.action,
                entry.record.parent,
                entry.record.helper,
                entry.record.reservation,
            )
        }
        val released = entry.lease?.release() ?: HelperReleaseResult.Released
        if (released == HelperReleaseResult.Released) {
            if (entry.isRestored || entry.hasJournalWriteStarted) journal.settle(entry.record)
            true
        } else {
            false
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(harnessScriptFailure(error)) { "Helper cleanup remains unresolved" }
        false
    }

    private fun isFenced(owner: HarnessSpawnOwner): Boolean =
        owner.generation <= (harnessFences[owner.harness] ?: Long.MIN_VALUE) ||
            owner.generation <= (itemFences[owner.harness to owner.item] ?: Long.MIN_VALUE)
}

/** Immutable prepared submission, with authority captured by the host before the first suspension. */
internal data class HarnessSpawnSubmission(
    val record: HarnessSpawnRecord,
    val workspace: WorkspaceRef?,
    val title: String,
    val prompt: HelperPrompt,
    val isAdmitted: suspend () -> Boolean,
) {
    override fun toString(): String = "HarnessSpawnSubmission(***)"
}

private fun <K> MutableMap<K, Long>.raise(key: K, generation: Long) {
    this[key] = maxOf(this[key] ?: Long.MIN_VALUE, generation)
}

/** Old-profile generations cannot be compared with this profile's newly initialized activation counter. */
private fun HarnessSpawnEntry.isCoveredBy(fence: HarnessSpawnOwner): Boolean = record.owner.let {
    it.harness == fence.harness && it.item == fence.item && (isRestored || it.generation <= fence.generation)
}

private fun HarnessEffect.Deactivate.covers(entry: HarnessSpawnEntry): Boolean = entry.record.owner.let { owner ->
    (owner.harness in harnesses && (entry.isRestored || owner.generation <= generation)) || items.any {
        owner.harness == it.harness.id && owner.item == it.item.id &&
            (entry.isRestored || owner.generation <= it.generation)
    }
}
