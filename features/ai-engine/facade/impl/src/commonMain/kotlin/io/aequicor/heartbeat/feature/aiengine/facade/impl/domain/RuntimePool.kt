package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

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
 */
class RuntimePool(
    private val context: FacadeContext,
    private val hasActiveTurn: (EngineId, AuthSourceId) -> Boolean,
    private val retireHandles: suspend (EngineId, AuthSourceId) -> Unit,
    private val launchOf: suspend (EngineId) -> LaunchContext = { LaunchContext() },
) {
    private val log = Log.tag("RuntimePool")
    private val mutex = Mutex()
    private val runtimes = mutableMapOf<Pair<EngineId, AuthSourceId>, Pooled>()
    private val keyLocks = mutableMapOf<Pair<EngineId, AuthSourceId>, Mutex>()
    private val published = MutableStateFlow(emptyList<RuntimeEntry>())
    private var isClosed = false

    /** Pooled runtimes; one that shut itself down stays listed until it is replaced, pruned or retired. */
    val entries: StateFlow<List<RuntimeEntry>> = published.asStateFlow()

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
     * Retires every idle runtime of [engine]. A runtime with an accepted turn is kept and counted as busy: the
     * caller retries once it is idle. A runtime that shut itself down is disposed whatever its handles report.
     */
    suspend fun retire(engine: EngineId): RetireOutcome {
        val keys = mutex.withLock { runtimes.keys.filter { it.first == engine } }
        var retired = 0
        var busy = 0
        keys.forEach { key ->
            locked(key) {
                val current = mutex.withLock { runtimes[key] } ?: return@locked
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
            runtimes[key]
        }
        if (current != null && current.runtime.isClosed) {
            dispose(current, key)
        } else if (current != null && current.runtime.identity == identity) {
            return current.runtime
        } else if (current != null) {
            retire(current, key)
        }
        mutex.withLock { ensureOpen(identity.engine) }
        log.i { "start runtime engine=${identity.engine.value} source=${identity.source.value}" }
        // Read before the adapter starts, so a change made meanwhile marks the runtime stale rather than current.
        val launch = launchOf(identity.engine)
        val created = create(resolved)
        if (created.identity != identity) {
            log.e { "runtime reported another identity engine=${identity.engine.value}" }
            withContext(NonCancellable) { closeQuietly(created) }
            fail(EngineFailure.Unknown())
        }
        // Registration must not be cancelled between creation and the pool, or the runtime would leak.
        val isStored = withContext(NonCancellable) {
            mutex.withLock {
                if (isClosed) {
                    false
                } else {
                    runtimes[key] = Pooled(created, context.clock.now(), launch)
                    publish()
                    true
                }
            }
        }
        if (!isStored) {
            log.w { "runtime started during profile shutdown engine=${identity.engine.value}" }
            withContext(NonCancellable) { closeQuietly(created) }
            fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        }
        return created
    }

    /**
     * Creation is not interrupted by cancellation, and a runtime the adapter already started reaches the pool or is
     * closed here: `withContext` drops its result when the caller was cancelled meanwhile, so it is captured inside.
     */
    private suspend fun create(resolved: ResolvedRoute): EngineRuntime {
        val identity = resolved.identity
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
                withContext(NonCancellable) { closeQuietly(orphan) }
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
        if (isOwned) withContext(NonCancellable) { closeQuietly(current.runtime) }
    }

    /** Called under [mutex]. */
    private fun publish() {
        published.value = runtimes.map { (key, pooled) ->
            RuntimeEntry(key.first, key.second, pooled.startedAt, pooled.runtime.isClosed, pooled.launch)
        }
        log.d { "pooled runtimes count=${runtimes.size}" }
    }

    private fun ensureOpen(engine: EngineId) {
        if (isClosed) {
            log.w { "runtime requested after profile shutdown engine=${engine.value}" }
            fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        }
    }

    /** Closes every runtime at profile shutdown; later [runtime] calls fail with ProfileClosed. */
    suspend fun closeAll() {
        val all = mutex.withLock {
            isClosed = true
            runtimes.values.map { it.runtime }.also {
                runtimes.clear()
                publish()
            }
        }
        log.i { "close runtimes count=${all.size}" }
        // Already unregistered: every one is closed even if shutdown is cancelled midway. Each close runs in its own
        // child, so a cancellation thrown by one adapter close does not skip the others.
        withContext(NonCancellable) {
            coroutineScope { all.forEach { runtime -> launch { closeQuietly(runtime) } } }
        }
    }

    private suspend fun closeQuietly(runtime: EngineRuntime) {
        try {
            runtime.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "runtime close failed engine=${runtime.identity.engine.value}" }
        }
    }
}

/** One pooled runtime, when the pool started it and the launch context it started with. */
private data class Pooled(val runtime: EngineRuntime, val startedAt: Instant, val launch: LaunchContext)

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
