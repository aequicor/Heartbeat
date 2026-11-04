package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.withoutLabel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteAdmission
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.time.TimeMark

/**
 * Profile pool of native runtimes, one per engine and source across workspaces. A changed source revision
 * retires the old runtime before its replacement starts, so two credential rotators never run at once; while
 * [hasActiveTurn] reports an accepted turn on the old runtime, the new route is refused as Busy instead.
 * Idle handles still open on the old runtime are closed through [retireHandles] before it stops, so no handle
 * outlives its runtime. Retirement and creation are serialized per engine and source, so a hanging close of one
 * source never blocks runtimes of others. After [closeAll] the pool refuses new runtimes with ProfileClosed.
 *
 * A runtime that shut itself down ([EngineRuntime.isClosed]: a crashed process, a changed account) can run nothing:
 * it is disposed without the Busy check and replaced on the next request, and [prune] disposes it on demand.
 * [retire] stops the idle runtimes of one engine (an engine switched off, changed launch settings, a restart).
 * Explicit [disconnect] instead stops even accepted work of the removed binding's engine/source and revokes
 * stale resolved routes, including a runtime whose creation was still in flight when removal began.
 */
class RuntimePool(
    private val context: FacadeContext,
    private val hasActiveTurn: (EngineId, AuthSourceId) -> Boolean,
    private val retireHandles: suspend (EngineId, AuthSourceId) -> Unit,
    private val launchOf: suspend (EngineId) -> LaunchContext = { LaunchContext() },
) {
    private val log = Log.tag("RuntimePool")
    private val mutex = Mutex()
    private val cleanup = RuntimeCleanupOwner(context)
    private val admission = RuntimeAdmission(context, cleanup)
    private val runtimes = mutableMapOf<Pair<EngineId, AuthSourceId>, Pooled>()
    private val keyLocks = mutableMapOf<Pair<EngineId, AuthSourceId>, Mutex>()
    private val disconnected = mutableSetOf<Pair<EngineId, EngineBindingId>>()
    private val pendingDisconnects = mutableSetOf<Pair<EngineId, AuthSourceId>>()
    private val published = MutableStateFlow(emptyList<RuntimeEntry>())
    private val hasLiveRuntimes = MutableStateFlow(false)
    private var isClosed = false

    /** Shared cleanup ownership for restricted acquisition and ordinary runtime orphan cleanup. */
    internal val admissionCleanup: RuntimeAdmissionCleanup get() = cleanup

    /**
     * Pooled runtimes; observers receive native exit changes within one second. An exited runtime stays listed
     * until it is replaced, pruned or retired. Sampling stops when there are no observers.
     */
    val entries: StateFlow<List<RuntimeEntry>> = published.asStateFlow()

    init {
        // EngineRuntime exposes a Boolean, so sample while diagnostics has observers; no timer when unobserved.
        context.scope.launch(context.io) {
            combine(published.subscriptionCount, hasLiveRuntimes) { observers, live -> observers > 0 && live }
                .distinctUntilChanged().collectLatest { isObserved ->
                    if (isObserved) {
                        while (isActive) {
                            delay(RUNTIME_CHECK_MILLIS)
                            mutex.withLock { publish() }
                        }
                    }
                }
        }
    }

    /** Runtime of the checked [resolved] route, created on first use. */
    suspend fun runtime(resolved: ResolvedRoute): EngineRuntime {
        val identity = resolved.identity
        val key = identity.engine to identity.source
        val keyLock = mutex.withLock {
            ensureOpen(identity.engine)
            keyLocks.getOrPut(key) { Mutex() }
        }
        return keyLock.withLock { replace(resolved, key) }
    }

    /**
     * Runtime authorized to create/resume sessions. A fresh restricted candidate must prove the selected
     * artifact and baseline; metadata runtimes do not acquire admission by sharing credential identity.
     * [deadline] includes source-lock waiting and canonical [recheck]. Cleanup is always awaited.
     */
    suspend fun sessionRuntime(
        resolved: ResolvedRoute,
        deadline: TimeMark,
        recheck: suspend () -> Unit,
        prepare: suspend () -> Unit = {},
    ): EngineRuntime {
        if (deadline.hasPassedNow()) fail(EngineFailure.Transport(TransportFailureReason.Timeout))
        return withContext(context.io) {
            withTimeout(-deadline.elapsedNow()) {
                val key = resolved.identity.engine to resolved.identity.source
                locked(key) {
                    mutex.withLock {
                        ensureOpen(key.first)
                        requireConnected(resolved)
                    }
                    cleanup.retryFor(key.first, key.second)
                    acquireSession(resolved, deadline, recheck, prepare)
                }
            }
        }
    }

    /**
     * Assesses whether [resolved] could create a session without starting, reusing or transferring an ordinary
     * runtime: one restricted candidate is acquired for the exact identity, launch context and provider, then
     * assessed against the connector policy and closed. The source lock serializes the probe with runtime
     * replacement, and [recheck] must still authorize the canonical route. Cleanup is always awaited.
     */
    suspend fun assessAdmission(
        resolved: ResolvedRoute,
        deadline: TimeMark,
        recheck: suspend () -> Unit,
    ): RouteAdmission {
        if (deadline.hasPassedNow()) fail(EngineFailure.Transport(TransportFailureReason.Timeout))
        return withContext(context.io) {
            withTimeout(-deadline.elapsedNow()) {
                val identity = resolved.identity
                val key = identity.engine to identity.source
                locked(key) {
                    mutex.withLock {
                        ensureOpen(identity.engine)
                        requireConnected(resolved)
                    }
                    val launch = launchOf(identity.engine)
                    admission.assess(
                        resolved.registration,
                        EngineRuntimeCandidateRequest(identity, launch, resolved.source.scope.provider),
                        deadline,
                        recheck = {
                            mutex.withLock {
                                ensureOpen(identity.engine)
                                requireConnected(resolved)
                            }
                            recheck()
                            if (launchOf(identity.engine) != launch) {
                                fail(EngineFailure.Session(SessionFailureReason.Changed))
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Stops may reuse the already admitted runtime even when a fresh probe is unavailable: cancelling accepted
     * work must not replace its owner. An unadmitted or differently routed runtime is never reused for this purpose.
     */
    suspend fun runtimeForStop(
        resolved: ResolvedRoute,
        deadline: TimeMark,
        recheck: suspend () -> Unit,
        prepare: suspend () -> Unit,
    ): EngineRuntime {
        if (deadline.hasPassedNow()) fail(EngineFailure.Transport(TransportFailureReason.Timeout))
        val retained = withContext(context.io) {
            withTimeout(-deadline.elapsedNow()) {
                val key = resolved.identity.engine to resolved.identity.source
                locked(key) {
                    mutex.withLock {
                        ensureOpen(key.first)
                        requireConnected(resolved)
                    }
                    recheck()
                    val current = mutex.withLock { runtimes[key] }
                    if (current == null || !current.canStop(resolved)) {
                        null
                    } else {
                        prepare()
                        recheck()
                        current.runtime
                    }
                }
            }
        }
        return retained ?: sessionRuntime(resolved, deadline, recheck, prepare)
    }

    private suspend fun acquireSession(
        resolved: ResolvedRoute,
        deadline: TimeMark,
        recheck: suspend () -> Unit,
        prepare: suspend () -> Unit = {},
    ): EngineRuntime {
        val identity = resolved.identity
        val launch = launchOf(identity.engine)
        val result = admission.acquire(
            resolved.registration,
            EngineRuntimeCandidateRequest(identity, launch, resolved.source.scope.provider),
            deadline,
            recheck = {
                mutex.withLock {
                    ensureOpen(identity.engine)
                    requireConnected(resolved)
                }
                recheck()
                if (launchOf(identity.engine) != launch) fail(EngineFailure.Session(SessionFailureReason.Changed))
            },
            reuseOrRetire = { decision ->
                reuseOrRetire(resolved, launch, decision).also { prepare() }
            },
        )
        val accepted = when (result) {
            RuntimeAdmissionResult.Unavailable -> fail(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))

            is RuntimeAdmissionResult.Rejected -> fail(
                EngineFailure.Engine(
                    if (result.decision.status == Compatibility.Incompatible) {
                        EngineFailureReason.Incompatible
                    } else {
                        EngineFailureReason.CompatibilityUnknown
                    },
                ),
            )

            is RuntimeAdmissionResult.Accepted -> result
        }
        withContext(NonCancellable) {
            mutex.withLock {
                // Store before any cancellable boundary: pool/shutdown now owns every transferred runtime.
                val key = identity.engine to identity.source
                val existing = runtimes[key]
                val startedAt = existing?.takeIf { it.runtime === accepted.runtime }?.startedAt ?: context.clock.now()
                runtimes[key] = Pooled(
                    accepted.runtime,
                    startedAt,
                    launch,
                    resolved.source.withoutLabel(),
                    accepted.decision,
                )
                publish()
            }
        }
        mutex.withLock {
            ensureOpen(identity.engine)
            requireConnected(resolved)
        }
        log.i { "Admitted session runtime engine=${identity.engine.value} reused=${!accepted.isNew}" }
        return accepted.runtime
    }

    private suspend fun reuseOrRetire(
        resolved: ResolvedRoute,
        launch: LaunchContext,
        decision: EngineCompatibilityDecision,
    ): EngineRuntime? {
        val key = resolved.identity.engine to resolved.identity.source
        val current = mutex.withLock { runtimes[key] } ?: return null
        val runtime = current.runtime
        if (current.isReusable(resolved, launch, decision)) return runtime
        if (runtime.isClosed) dispose(current, key) else retire(current, key)
        cleanup.retryFor(key.first, key.second)
        return null
    }

    /**
     * Stops the removed [binding]'s runtime even while a native turn runs without an open facade handle. The
     * process stops before handle cleanup, so releasing a slow handle cannot keep the request running. Failed
     * shutdown keeps the runtime for retry; unrelated engine/source pairs are neither closed nor blocked.
     */
    suspend fun disconnect(binding: EngineBinding) {
        val key = binding.engine to binding.authSource
        withContext(NonCancellable) {
            mutex.withLock {
                disconnected += binding.engine to binding.id
                pendingDisconnects += key
            }
            locked(key) {
                val current = mutex.withLock { runtimes[key] }
                log.i { "disconnect runtime engine=${binding.engine.value} source=${binding.authSource.value}" }
                try {
                    if (current != null) {
                        // Do not use quiet retirement: the caller must know if forced shutdown failed.
                        current.runtime.close()
                        mutex.withLock {
                            if (runtimes[key] === current) {
                                runtimes.remove(key)
                                publish()
                            }
                        }
                    }
                    mutex.withLock { pendingDisconnects -= key }
                } finally {
                    withContext(NonCancellable) { retireHandles(key.first, key.second) }
                }
            }
        }
    }

    /**
     * Retires every idle runtime of [engine]. A runtime with an accepted turn is kept and counted as busy: the
     * caller retries once it is idle. A runtime that shut itself down is disposed whatever its handles report.
     * When [expected] is supplied, live runtimes already using that context are preserved.
     */
    suspend fun retire(engine: EngineId, expected: LaunchContext? = null): RetireOutcome {
        val keys = mutex.withLock { runtimes.keys.filter { it.first == engine } }
        var retired = 0
        var busy = 0
        keys.forEach { key ->
            locked(key) {
                if (mutex.withLock { key in pendingDisconnects }) {
                    busy++
                    return@locked
                }
                val current = mutex.withLock { runtimes[key] } ?: return@locked
                if (expected != null && !current.runtime.isClosed && current.launch == expected) return@locked
                if (current.runtime.isClosed) {
                    dispose(current, key)
                    retired++
                } else if (retireIdle(current, key)) {
                    retired++
                } else {
                    busy++
                }
            }
        }
        log.i { "retire engine=${engine.value} retired=$retired busy=$busy" }
        return RetireOutcome(retired, busy)
    }

    /** Disposes runtimes that shut themselves down; live runtimes are kept. Returns how many were disposed. */
    suspend fun prune(): Int {
        val closed = mutex.withLock { runtimes.filterValues { it.runtime.isClosed }.keys.toList() }
        var disposed = 0
        closed.forEach { key ->
            locked(key) {
                if (mutex.withLock { key in pendingDisconnects }) return@locked
                val current = mutex.withLock { runtimes[key] }
                if (current != null && current.runtime.isClosed) {
                    dispose(current, key)
                    disposed++
                }
            }
        }
        if (disposed > 0) log.i { "pruned exited runtimes count=$disposed" }
        return disposed
    }

    private suspend fun <T> locked(key: Pair<EngineId, AuthSourceId>, block: suspend () -> T): T {
        val keyLock = mutex.withLock { keyLocks.getOrPut(key) { Mutex() } }
        return keyLock.withLock { block() }
    }

    private suspend fun replace(resolved: ResolvedRoute, key: Pair<EngineId, AuthSourceId>): EngineRuntime {
        val identity = resolved.identity
        val current = mutex.withLock {
            ensureOpen(identity.engine)
            requireConnected(resolved)
            runtimes[key]
        }
        cleanup.retryFor(identity.engine, identity.source)
        if (current != null && current.runtime.isClosed) {
            dispose(current, key)
        } else if (current != null && current.runtime.identity == identity &&
            current.source == resolved.source.withoutLabel()
        ) {
            return current.runtime
        } else if (current != null) {
            retire(current, key)
        }
        mutex.withLock { ensureOpen(identity.engine) }
        log.i { "start runtime engine=${identity.engine.value} source=${identity.source.value}" }
        // Read before the adapter starts, so a change made meanwhile marks the runtime stale rather than current.
        val launch = launchOf(identity.engine)
        // Retirement can leave an unconfirmed close: never start another credential rotator over that orphan.
        cleanup.retryFor(identity.engine, identity.source)
        val created = create(resolved)
        if (created.identity != identity) {
            log.e { "runtime reported another identity engine=${identity.engine.value}" }
            withContext(NonCancellable) { closeQuietly(created, identity) }
            fail(EngineFailure.Unknown())
        }
        // Registration must not be cancelled between creation and the pool, or the runtime would leak.
        val isStored = withContext(NonCancellable) {
            mutex.withLock {
                // Shutdown waits for this source lock and owns retries if stopping this late runtime fails.
                runtimes[key] = Pooled(created, context.clock.now(), launch, resolved.source.withoutLabel())
                publish()
                !isClosed
            }
        }
        if (!isStored) {
            log.w { "runtime started during profile shutdown engine=${identity.engine.value}" }
            withContext(NonCancellable) { closeOwned(key) }
            fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        }
        // A disconnect waiting for this source lock owns its shutdown, including retry if close fails.
        mutex.withLock { requireConnected(resolved) }
        return created
    }

    /**
     * Optional preparation remains cancellable. Native creation is not interrupted; a started runtime reaches
     * the pool or is closed here: `withContext` drops its result on cancellation, so it is captured inside.
     */
    private suspend fun create(resolved: ResolvedRoute): EngineRuntime {
        val identity = resolved.identity
        adapterCall(log, "prepareRuntime") {
            withContext(context.io) { resolved.registration.factory.value.prepareRuntime() }
        }
        currentCoroutineContext().ensureActive()
        var started: EngineRuntime? = null
        var isHandedOver = false
        try {
            val created = adapterCall(log, "createRuntime") {
                withContext(NonCancellable + context.io) {
                    resolved.registration.factory.value.createRuntime(identity).also { started = it }
                }
            }
            currentCoroutineContext().ensureActive()
            isHandedOver = true
            return created
        } finally {
            // Once the adapter started it, only cancellation keeps the runtime from reaching the caller.
            val orphan = started?.takeUnless { isHandedOver }
            if (orphan != null) {
                log.i { "runtime created for a cancelled request, closing engine=${identity.engine.value}" }
                withContext(NonCancellable) { closeQuietly(orphan, identity) }
            }
        }
    }

    private suspend fun retire(current: Pooled, key: Pair<EngineId, AuthSourceId>) {
        val (engine, source) = key
        if (hasActiveTurn(engine, source)) {
            log.w { "runtime busy, not retired engine=${engine.value} source=${source.value}" }
            fail(EngineFailure.Session(SessionFailureReason.Busy))
        }
        log.i { "retire runtime engine=${engine.value} source=${source.value}" }
        unregister(current, key)
    }

    /** Retires [current] unless a turn is in flight on it, including one that started meanwhile. */
    private suspend fun retireIdle(current: Pooled, key: Pair<EngineId, AuthSourceId>): Boolean {
        if (hasActiveTurn(key.first, key.second)) return false
        return try {
            retire(current, key)
            true
        } catch (e: EngineException) {
            if (e.failure != EngineFailure.Session(SessionFailureReason.Busy)) throw e
            log.w(e) { "runtime became busy before it was retired engine=${key.first.value}" }
            false
        }
    }

    /** A runtime that shut itself down runs nothing, so its handles are closed even if they still report a turn. */
    private suspend fun dispose(current: Pooled, key: Pair<EngineId, AuthSourceId>) {
        val (engine, source) = key
        log.i { "dispose exited runtime engine=${engine.value} source=${source.value}" }
        unregister(current, key)
    }

    private suspend fun unregister(current: Pooled, key: Pair<EngineId, AuthSourceId>) {
        retireHandles(key.first, key.second)
        // Only the caller that unregisters the runtime closes it, so a concurrent closeAll never closes it twice.
        val isOwned = withContext(NonCancellable) {
            mutex.withLock {
                (runtimes[key] === current).also { isOwned ->
                    if (isOwned) {
                        runtimes.remove(key)
                        publish()
                    }
                }
            }
        }
        if (isOwned) withContext(NonCancellable) { cleanup.close(current.runtime, current.runtime.identity) }
    }

    /** Called under [mutex]. */
    private fun publish() {
        val next = runtimes.map { (key, pooled) ->
            RuntimeEntry(key.first, key.second, pooled.startedAt, pooled.runtime.isClosed, pooled.launch)
        }
        if (next != published.value) {
            published.value = next
            log.d { "pooled runtimes count=${runtimes.size}" }
        }
        hasLiveRuntimes.value = next.any { !it.isClosed }
    }

    private fun ensureOpen(engine: EngineId) {
        if (isClosed) {
            log.w { "runtime requested after profile shutdown engine=${engine.value}" }
            fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        }
    }

    /** Called under [mutex]; a deleted binding id cannot acquire another runtime from an earlier route snapshot. */
    private fun requireConnected(resolved: ResolvedRoute) {
        val identity = resolved.identity
        if ((identity.engine to resolved.binding.id) in disconnected ||
            (identity.engine to identity.source) in pendingDisconnects
        ) {
            log.w { "runtime refused for disconnected binding=${resolved.binding.id.value}" }
            fail(OperationNotAllowed)
        }
    }

    /**
     * Closes every runtime, including creation already in flight; later [runtime] calls fail with ProfileClosed.
     * Concurrent callers await the same source locks. Failed closes remain owned and are retried on the next call.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun closeAll() {
        val keys = mutex.withLock {
            isClosed = true
            keyLocks.keys.toList()
        }
        log.i { "close runtimes count=${keys.size}" }
        // Share retirement's source locks: an explicit disconnect may already be closing the same runtime.
        // Each close has its own child, so a cancellation thrown by one adapter does not skip the others.
        withContext(NonCancellable) {
            val failures = supervisorScope {
                val jobs = keys.map { key -> async { locked(key) { closeOwned(key) } } }
                jobs.forEach { it.join() }
                // Source locks have drained, so even a late cancelled creation has registered its orphan.
                val remaining = async { cleanup.retryAll() }
                remaining.join()
                (jobs + remaining).mapNotNull { it.getCompletionExceptionOrNull() }
            }
            failures.firstOrNull()?.let { first ->
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    /** Called under the source lock; removes ownership only after the adapter confirms cleanup completed. */
    private suspend fun closeOwned(key: Pair<EngineId, AuthSourceId>) {
        val current = mutex.withLock { runtimes[key] } ?: return
        current.runtime.close()
        mutex.withLock {
            if (runtimes[key] === current) {
                runtimes.remove(key)
                publish()
            }
        }
    }

    private suspend fun closeQuietly(runtime: EngineRuntime, expected: RuntimeIdentity) {
        try {
            cleanup.close(runtime, expected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "runtime close failed engine=${runtime.identity.engine.value}" }
        }
    }

    private companion object {
        const val RUNTIME_CHECK_MILLIS = 1_000L
    }
}

/** One pooled runtime, when the pool started it and the launch context it started with. */
private data class Pooled(
    val runtime: EngineRuntime,
    val startedAt: Instant,
    val launch: LaunchContext,
    val source: AuthSource,
    val admission: EngineCompatibilityDecision? = null,
) {
    fun canStop(resolved: ResolvedRoute): Boolean {
        val stamp = admission?.takeIf { it.isBaselineAllowed }?.stamp ?: return false
        if (runtime.isClosed || runtime.identity != resolved.identity) return false
        if (runtime.compatibilityStamp != stamp) return false
        return source == resolved.source.withoutLabel()
    }

    fun isReusable(resolved: ResolvedRoute, launch: LaunchContext, decision: EngineCompatibilityDecision): Boolean {
        if (runtime.isClosed || runtime.identity != resolved.identity || this.launch != launch) return false
        if (source != resolved.source.withoutLabel()) return false
        return admission?.stamp == decision.stamp && runtime.compatibilityStamp == decision.stamp
    }
}

/**
 * A pooled runtime of [engine] and [source]; [isClosed] tells it shut itself down and awaits replacement, and
 * [launch] is the context it started with, which tells whether later launch changes reached it.
 */
data class RuntimeEntry(
    val engine: EngineId,
    val source: AuthSourceId,
    val startedAt: Instant,
    val isClosed: Boolean,
    val launch: LaunchContext = LaunchContext(),
)

/** Runtimes [RuntimePool.retire] stopped and the busy ones it kept. */
data class RetireOutcome(val retired: Int, val busy: Int)
