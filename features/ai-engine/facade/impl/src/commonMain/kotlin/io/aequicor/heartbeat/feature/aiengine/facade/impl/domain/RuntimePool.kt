package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Profile pool of native runtimes, one per engine and source across workspaces. A changed source revision
 * retires the old runtime before its replacement starts, so two credential rotators never run at once; while
 * [hasActiveTurn] reports an accepted turn on the old runtime, the new route is refused as Busy instead.
 * Idle handles still open on the old runtime are closed through [retireHandles] before it stops, so no handle
 * outlives its runtime. Retirement and creation are serialized per engine and source, so a hanging close of one
 * source never blocks runtimes of others. After [closeAll] the pool refuses new runtimes with ProfileClosed.
 */
class RuntimePool(
    private val context: FacadeContext,
    private val hasActiveTurn: (EngineId, AuthSourceId) -> Boolean,
    private val retireHandles: suspend (EngineId, AuthSourceId) -> Unit,
) {
    private val log = Log.tag("RuntimePool")
    private val mutex = Mutex()
    private val runtimes = mutableMapOf<Pair<EngineId, AuthSourceId>, EngineRuntime>()
    private val keyLocks = mutableMapOf<Pair<EngineId, AuthSourceId>, Mutex>()
    private var isClosed = false

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

    private suspend fun replace(resolved: ResolvedRoute, key: Pair<EngineId, AuthSourceId>): EngineRuntime {
        val identity = resolved.identity
        val current = mutex.withLock {
            ensureOpen(identity.engine)
            runtimes[key]
        }
        if (current != null && current.identity == identity) return current
        if (current != null) retire(current, key)
        mutex.withLock { ensureOpen(identity.engine) }
        log.i { "start runtime engine=${identity.engine.value} source=${identity.source.value}" }
        val created = adapterCall(log, "createRuntime") {
            withContext(context.io) { resolved.registration.factory.value.createRuntime(identity) }
        }
        if (created.identity != identity) {
            log.e { "runtime reported another identity engine=${identity.engine.value}" }
            closeQuietly(created)
            fail(EngineFailure.Unknown())
        }
        // Registration must not be cancelled between creation and the pool, or the runtime would leak.
        val stored = withContext(NonCancellable) {
            mutex.withLock { if (isClosed) false else true.also { runtimes[key] = created } }
        }
        if (!stored) {
            log.w { "runtime started during profile shutdown engine=${identity.engine.value}" }
            withContext(NonCancellable) { closeQuietly(created) }
            fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        }
        return created
    }

    private suspend fun retire(current: EngineRuntime, key: Pair<EngineId, AuthSourceId>) {
        val (engine, source) = key
        if (hasActiveTurn(engine, source)) {
            log.w { "runtime busy, not retired engine=${engine.value} source=${source.value}" }
            fail(EngineFailure.Session(SessionFailureReason.Busy))
        }
        log.i { "retire runtime engine=${engine.value} source=${source.value}" }
        retireHandles(engine, source)
        // Only the caller that unregisters the runtime closes it, so a concurrent closeAll never closes it twice.
        val owned = withContext(NonCancellable) {
            mutex.withLock { (runtimes[key] === current).also { if (it) runtimes.remove(key) } }
        }
        if (owned) withContext(NonCancellable) { closeQuietly(current) }
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
            runtimes.values.toList().also { runtimes.clear() }
        }
        log.i { "close runtimes count=${all.size}" }
        all.forEach { closeQuietly(it) }
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
