package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessReceipt
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRemovalResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Profile-owned code lifecycle, independent of DI and persistent workflow drivers. Compilation and evaluation
 * stage a candidate; only a successful exact Pending generation replaces the current instance. Fences are
 * retained before cancellation or cleanup. Retired code remains owned until actual execution has drained.
 */
internal class HarnessRuntime(
    private val host: HarnessScriptHost,
    private val lane: HarnessExecutionLane,
    private val environment: HarnessRuntimeEnvironment,
) {
    private val mutex = Mutex()

    /** Item-ordered cache IO; a startup sweep goes straight through it, outside any generation fence. */
    val cache = HarnessRuntimeCache(host)
    private val declarations = HarnessToolDeclarations()
    private val current = mutableMapOf<HarnessRuntimeKey, HarnessInstance>()
    private val publication = MutableStateFlow<List<HarnessInstance>>(emptyList())
    private val pending = mutableMapOf<HarnessRuntimeKey, HarnessPreparation>()
    private val retiring = mutableSetOf<HarnessInstance>()
    private val generations = mutableMapOf<HarnessRuntimeKey, Long>()
    private val itemFences = mutableMapOf<HarnessRuntimeKey, Long>()
    private val harnessFences = mutableMapOf<HarnessId, Long>()
    private val removals = mutableMapOf<HarnessId, HarnessRemovalFence>()
    private val log = Log.tag("HarnessRuntime")

    val isAvailable: Boolean get() = host.isAvailable

    /** Published script names remain catalogued after disable, without loading or evaluating author code. */
    val declaredTools: List<AgentToolSpec> get() = declarations.specifications

    /**
     * Exact published generations, ordered by harness and item. Updates share the runtime mutation mutex.
     * This lifecycle snapshot grants no invocation authority: admission may already have changed, and callers
     * must enter through [invoke] even when they retained a handle from the latest snapshot.
     */
    val publishedInstances: StateFlow<List<HarnessInstance>> = publication.asStateFlow()

    /**
     * Cancelling a waiter does not revoke an approved profile-owned activation. An exhausted lane delays a new
     * activation until a worker frees up instead of failing it on a momentary snapshot; fences and admission are
     * rechecked at least every [CAPACITY_RECHECK], so a request that became stale while waiting fails at once
     * instead of holding the rest of its Activate batch behind the lane.
     */
    suspend fun activate(request: HarnessActivationRequest): Boolean {
        log.v { "prepare harness activation" }
        var preparation: HarnessPreparation? = null
        var isRejected = false
        var waited = Duration.ZERO
        while (preparation == null && !isRejected) {
            preparation = mutex.withLock {
                existingPreparation(request) ?: when {
                    !isPreparable(request) -> null.also { isRejected = true }
                    lane.isExhausted -> null
                    else -> reservePreparation(request).also { isRejected = it == null }
                }
            }
            if (preparation == null && !isRejected) {
                waited = lane.awaitCapacity(CAPACITY_RECHECK, waited, HarnessInvocationBudget.Evaluation)
            }
        }
        preparation?.job?.start()
        return preparation?.result?.await() ?: false
    }

    /** Returns only an admitted current generation; retaining the handle never bypasses invoke's recheck. */
    suspend fun instance(harness: HarnessId, item: ItemId): HarnessInstance? = mutex.withLock {
        current[HarnessRuntimeKey(harness, item)]?.takeIf { it.isActive }
    }

    /** Ordered current handles; every dispatcher must still enter through invoke before author code runs. */
    suspend fun published(): List<HarnessInstance> = mutex.withLock {
        publication.value.filter { it.isActive }
    }

    @HighFrequency
    suspend fun <T> invoke(
        instance: HarnessInstance,
        budget: Duration,
        options: HarnessInvocationOptions = HarnessInvocationOptions(),
        block: suspend () -> T,
    ): HarnessInvocationResult<HarnessAttempt<T>>? {
        log.v { "admit harness callback" }
        val isAdmitted = mutex.withLock {
            (current[instance.request.key()] === instance && instance.isActive).also {
                if (it) instance.reserveCall()
            }
        }
        if (!isAdmitted) return null
        try {
            val result = instance.calls.run(instance.dispatcher, budget, options) { captureHarnessFailure(block) }
            if (result is HarnessInvocationResult.TimedOut) {
                log.w(ScriptFailure()) { "Script callback timed out" }
            }
            if (result !is HarnessInvocationResult.Cancelled || !result.isExpected) {
                recordOutcome(instance, result.hasFailed())
            }
            return result
        } finally {
            instance.releaseCall()
        }
    }

    /** Returns a snapshot of quiescence; callers retry false without weakening already installed fences. */
    suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean = mutex.withLock {
        log.v { "revoke harness generations" }
        effect.items.forEach { itemFences.raise(it.key(), it.generation) }
        effect.harnesses.forEach { harnessFences.raise(it, effect.generation) }
        revokeFenced()
        ownedInstances().filter { effect.covers(it.request) }.all { it.isClosed }
    }

    /** Installs a bounded fence once per exact deletion receipt, before any drain or persistent IO. */
    suspend fun revokeHarness(effect: HarnessEffect.Remove): HarnessRemovalResult = mutex.withLock {
        log.v { "revoke harness deletion generation" }
        val generation = environment.admission.removalGeneration(effect)
            ?: return@withLock HarnessRemovalResult.Obsolete
        val previous = removals[effect.harness.id]
        val fence = previous?.takeIf { it.receipt == effect.receipt }
            ?: HarnessRemovalFence(effect.receipt, generation).also { removals[effect.harness.id] = it }
        harnessFences.raise(effect.harness.id, fence.generation)
        revokeFenced()
        removalStatusLocked(effect)
    }

    /** Rechecks the exact pending receipt after every suspend boundary in the deletion adapter. */
    suspend fun removalStatus(effect: HarnessEffect.Remove): HarnessRemovalResult = mutex.withLock {
        removalStatusLocked(effect)
    }

    /** The fence captured for this still-current receipt; retries must never widen it to a newer generation. */
    suspend fun removalGeneration(effect: HarnessEffect.Remove): Long? = mutex.withLock {
        if (environment.admission.removalGeneration(effect) == null) return@withLock null
        removals[effect.harness.id]?.takeIf { it.receipt == effect.receipt }?.generation
    }

    /**
     * The fence captured for [effect] even after its receipt stopped being pending, so cleanup of a deletion that
     * was committed before its code drained keeps exactly the original barrier.
     */
    suspend fun committedRemovalGeneration(effect: HarnessEffect.Remove): Long? = mutex.withLock {
        removals[effect.harness.id]?.takeIf { it.receipt == effect.receipt }?.generation
    }

    private fun removalStatusLocked(effect: HarnessEffect.Remove): HarnessRemovalResult {
        if (environment.admission.removalGeneration(effect) == null) return HarnessRemovalResult.Obsolete
        val fence = removals[effect.harness.id]?.takeIf { it.receipt == effect.receipt }
        val isDrained = fence != null && ownedInstances().filter {
            it.request.harness.id == effect.harness.id && it.request.generation <= fence.generation
        }.all { it.isClosed }
        return if (isDrained) HarnessRemovalResult.Ready else HarnessRemovalResult.Retry
    }

    /** Item deletion follows the actual code barrier; delayed effects cannot evict a recreated item's cache. */
    suspend fun removeObsoleteCache(items: List<HarnessActivationRequest>): Boolean {
        items.forEach { request ->
            val result = cache.remove(request.harness.id, request.item.id) {
                mutex.withLock {
                    isFenced(request) && request.generation >= (generations[request.key()] ?: Long.MIN_VALUE) &&
                        ownedInstances().filter { it.request.key() == request.key() }.all { it.isClosed } &&
                        environment.admission.canRemoveCached(request)
                }
            }
            if (result == HarnessCacheRemoval.Failed) return false
        }
        return true
    }

    /**
     * Disk cache of a deletion committed before its code drained. Retired code runs from memory and host removal
     * invalidates already admitted compiler work, so the files go when the deletion commits instead of waiting for
     * hanging code. Each item is rechecked under its lock: this receipt's fence must still be recorded and the
     * committed library must no longer contain the item identity. Done once per receipt; false retries.
     */
    private suspend fun removeCommittedCache(effect: HarnessEffect.Remove): Boolean {
        val fence = mutex.withLock { removals[effect.harness.id]?.takeIf { it.receipt == effect.receipt } }
        if (fence == null || fence.isCacheRemoved) return true
        for (item in effect.harness.items.filter { it is HarnessItem.Script || it is HarnessItem.Workflow }) {
            val request = HarnessActivationRequest(effect.harness, item, fence.generation)
            val result = cache.remove(effect.harness.id, item.id) {
                mutex.withLock {
                    removals[effect.harness.id]?.receipt == effect.receipt &&
                        environment.admission.canRemoveCached(request)
                }
            }
            if (result == HarnessCacheRemoval.Failed) return false
        }
        mutex.withLock {
            val current = removals[effect.harness.id]
            if (current?.receipt == effect.receipt) removals[effect.harness.id] = current.copy(isCacheRemoved = true)
        }
        return true
    }

    /**
     * Cache cleanup shares the compile lock and rechecks the same receipt before and after host IO. With
     * [isCommitted] it is the cleanup of a deletion committed before its code drained (see [removeCommittedCache]):
     * Ready once the files are gone, Retry after failed IO.
     */
    suspend fun removeCached(effect: HarnessEffect.Remove, isCommitted: Boolean = false): HarnessRemovalResult = when {
        !isCommitted -> removePendingCache(effect)
        removeCommittedCache(effect) -> HarnessRemovalResult.Ready
        else -> HarnessRemovalResult.Retry
    }

    private suspend fun removePendingCache(effect: HarnessEffect.Remove): HarnessRemovalResult {
        for (item in effect.harness.items.filter { it is HarnessItem.Script || it is HarnessItem.Workflow }) {
            val result = cache.remove(effect.harness.id, item.id) {
                mutex.withLock { removalStatusLocked(effect) == HarnessRemovalResult.Ready }
            }
            if (result == HarnessCacheRemoval.Failed) return HarnessRemovalResult.Retry
        }
        return removalStatus(effect)
    }

    /** An already published or preparing exact request never waits for lane capacity. */
    private fun existingPreparation(request: HarnessActivationRequest): HarnessPreparation? {
        val key = request.key()
        val active = current[key]
        if (active?.request == request && active.isActive) return HarnessPreparation.completed(request)
        return pending[key]?.takeIf { it.request == request }
    }

    /** Called under [mutex] only after [existingPreparation] found no exact published or pending request. */
    private fun reservePreparation(request: HarnessActivationRequest): HarnessPreparation? {
        val key = request.key()
        val previous = pending[key]
        if (!isPreparable(request)) return null
        generations.raise(key, request.generation)
        previous?.revoke()
        return HarnessPreparation(request).also { preparation ->
            pending[key] = preparation
            preparation.job = environment.scope.launch(environment.dispatchers.default, start = CoroutineStart.LAZY) {
                prepare(preparation)
            }
            preparation.job?.invokeOnCompletion { preparation.result.complete(false) }
        }
    }

    /** Called under [mutex]: whether this exact request may still start a new preparation. */
    private fun isPreparable(request: HarnessActivationRequest): Boolean =
        host.isAvailable && environment.scope.isActive && !isFenced(request) &&
            request.generation >= (generations[request.key()] ?: Long.MIN_VALUE) &&
            environment.admission.canPublish(request) && request.compilation() != null

    private suspend fun prepare(preparation: HarnessPreparation) {
        var instance: HarnessInstance? = null
        var isPublished = false
        try {
            val result = captureHarnessFailure {
                val compiled = cache.compile(checkNotNull(preparation.request.compilation()))
                if (compiled is HarnessCompilationResult.Success) {
                    instance = construct(preparation, compiled)
                    instance?.let { evaluateAndPublish(preparation, it) } == true
                } else {
                    false
                }
            }
            isPublished = (result as? HarnessAttempt.Success)?.value == true
            preparation.result.complete(isPublished)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    instance?.releaseCall()
                    if (pending[preparation.request.key()] === preparation) pending.remove(preparation.request.key())
                    if (!isPublished) instance?.let(::retire)
                    preparation.result.complete(false)
                }
            }
        }
    }

    private suspend fun construct(
        preparation: HarnessPreparation,
        compiled: HarnessCompilationResult.Success,
    ): HarnessInstance? {
        var isTransferred = false
        try {
            return mutex.withLock {
                if (isCandidate(preparation)) {
                    HarnessInstance(
                        preparation.request,
                        compiled.code,
                        lane.instanceDispatcher(),
                        environment,
                        onBackgroundFailure = { recordOutcome(it, true) },
                    ).also {
                        it.reserveCall()
                        preparation.instance = it
                        isTransferred = true
                    }
                } else {
                    null
                }
            }
        } finally {
            if (!isTransferred) compiled.code.close()
        }
    }

    private suspend fun evaluateAndPublish(preparation: HarnessPreparation, instance: HarnessInstance): Boolean {
        val result = instance.calls.run(instance.dispatcher, HarnessInvocationBudget.Evaluation) {
            captureHarnessFailure { host.evaluate(instance.code, instance.prepareContext()) }
        }
        if (result is HarnessInvocationResult.TimedOut) {
            log.w(ScriptFailure()) { "Script evaluation timed out" }
        }
        val evaluation = (result as? HarnessInvocationResult.Completed)?.value as? HarnessAttempt.Success
        if (evaluation?.value != HarnessEvaluationResult.Success) return false
        return mutex.withLock {
            if (isCandidate(preparation) && declarations.accepts(instance, current.values) && instance.publish()) {
                declarations.published(instance)
                val old = current.put(preparation.request.key(), instance)
                old?.let(::retire)
                updatePublication()
                true
            } else {
                false
            }
        }
    }

    private fun isCandidate(preparation: HarnessPreparation): Boolean =
        pending[preparation.request.key()] === preparation && !isFenced(preparation.request) &&
            environment.scope.isActive && environment.admission.canPublish(preparation.request)

    private suspend fun recordOutcome(instance: HarnessInstance, hasFailed: Boolean) {
        val feedback = mutex.withLock {
            if (current[instance.request.key()] !== instance || !instance.isActive) return@withLock null
            instance.consecutiveFailures = if (hasFailed) instance.consecutiveFailures + 1 else 0
            if (!hasFailed) return@withLock null
            val isDisabled = instance.consecutiveFailures >= HarnessLimits.FAILURES
            if (isDisabled) {
                current.remove(instance.request.key())
                itemFences.raise(instance.request.key(), instance.request.generation)
                retire(instance)
            }
            HarnessIntent.Internal.ItemRuntimeFailed(
                instance.request.harness.id,
                instance.request.item.id,
                instance.request.harness.revision,
                instance.request.generation,
                isDisabled,
            )
        }
        if (feedback != null) {
            environment.scope.launch(environment.dispatchers.default) { environment.reportFailure(feedback) }
        }
    }

    private fun revokeFenced() {
        pending.values.filter { isFenced(it.request) }.forEach {
            pending.remove(it.request.key())
            it.revoke()
            it.instance?.let(::retire)
        }
        current.values.filter { isFenced(it.request) }.forEach {
            current.remove(it.request.key())
            retire(it)
        }
    }

    private fun retire(instance: HarnessInstance) {
        instance.retire()
        updatePublication()
        if (retiring.add(instance)) {
            environment.scope.launch(environment.dispatchers.default) {
                instance.drain()
                mutex.withLock { retiring.remove(instance) }
            }
        }
    }

    /** Called only while holding [mutex], after the corresponding current-map mutation. */
    @HighFrequency
    private fun updatePublication() {
        log.v { "update published harness instances" }
        publication.value = current.values.sortedWith(
            compareBy({ it.request.harness.name.value }, { it.request.item.id.value }),
        )
    }

    private fun ownedInstances(): List<HarnessInstance> =
        current.values + retiring + pending.values.mapNotNull { it.instance }

    private fun isFenced(request: HarnessActivationRequest): Boolean =
        request.generation <= (itemFences[request.key()] ?: Long.MIN_VALUE) ||
            request.generation <= (harnessFences[request.harness.id] ?: Long.MIN_VALUE)
}

private class HarnessPreparation(val request: HarnessActivationRequest) {
    val result = CompletableDeferred<Boolean>()
    var job: Job? = null
    var instance: HarnessInstance? = null

    fun revoke() {
        result.complete(false)
        job?.cancel()
    }

    companion object {
        fun completed(request: HarnessActivationRequest): HarnessPreparation =
            HarnessPreparation(request).apply { result.complete(true) }
    }
}

/** Longest wait for lane capacity before an activation rechecks that its request is still current. */
private val CAPACITY_RECHECK: Duration = 1.seconds

private data class HarnessRuntimeKey(val harness: HarnessId, val item: ItemId)
private fun HarnessActivationRequest.key(): HarnessRuntimeKey = HarnessRuntimeKey(harness.id, item.id)
private fun <K> MutableMap<K, Long>.raise(key: K, generation: Long) {
    this[key] = maxOf(this[key] ?: Long.MIN_VALUE, generation)
}

/** A host limit refusal ends the callback without counting as its failure. */
private fun HarnessInvocationResult<*>.hasFailed(): Boolean = this is HarnessInvocationResult.TimedOut ||
    ((this as? HarnessInvocationResult.Completed)?.value as? HarnessAttempt.Failure)?.error?.isRefusal == false ||
    (this is HarnessInvocationResult.Cancelled && !isExpected)

private fun HarnessActivationRequest.compilation(): HarnessCompilationRequest? = when (val code = item) {
    is HarnessItem.Script -> HarnessCompilationRequest(harness.id, code.id, HarnessCodeKind.Script, code.source)
    is HarnessItem.Workflow -> HarnessCompilationRequest(harness.id, code.id, HarnessCodeKind.Workflow, code.source)
    else -> null
}

private data class HarnessRemovalFence(
    val receipt: HarnessReceipt,
    val generation: Long,
    val isCacheRemoved: Boolean = false,
)

private fun HarnessEffect.Deactivate.covers(request: HarnessActivationRequest): Boolean =
    (request.harness.id in harnesses && request.generation <= generation) || items.any {
        it.harness.id == request.harness.id && it.item.id == request.item.id && request.generation <= it.generation
    }
