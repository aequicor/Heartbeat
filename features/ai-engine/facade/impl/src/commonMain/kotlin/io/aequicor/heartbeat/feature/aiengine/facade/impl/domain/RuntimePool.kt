package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Profile pool of native runtimes, one per engine and source across workspaces. A changed source revision
 * retires the old runtime before its replacement starts, so two credential rotators never run at once; while
 * [hasActiveTurn] reports an accepted turn on the old runtime, the new route is refused as Busy instead.
 * Idle handles still open on the old runtime are closed through [retireHandles] before it stops, so no handle
 * outlives its runtime. After [closeAll] the pool refuses new runtimes with ProfileClosed.
 */
class RuntimePool(
    private val context: FacadeContext,
    private val hasActiveTurn: (EngineId, AuthSourceId) -> Boolean,
    private val retireHandles: suspend (EngineId, AuthSourceId) -> Unit,
) {
    private val log = Log.tag("RuntimePool")
    private val mutex = Mutex()
    private val runtimes = mutableMapOf<Pair<EngineId, AuthSourceId>, EngineRuntime>()
    private var isClosed = false

    /** Runtime of the checked [resolved] route, created on first use. */
    suspend fun runtime(resolved: ResolvedRoute): EngineRuntime = mutex.withLock {
        if (isClosed) {
            log.w { "runtime requested after profile shutdown engine=${resolved.identity.engine.value}" }
            fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        }
        val identity = resolved.identity
        val key = identity.engine to identity.source
        val current = runtimes[key]
        if (current != null && current.identity == identity) return@withLock current
        if (current != null) {
            if (hasActiveTurn(identity.engine, identity.source)) {
                log.w { "runtime busy, not retired engine=${identity.engine.value} source=${identity.source.value}" }
                fail(EngineFailure.Session(SessionFailureReason.Busy))
            }
            log.i { "retire runtime engine=${identity.engine.value} source=${identity.source.value}" }
            retireHandles(identity.engine, identity.source)
            runtimes.remove(key)
            closeQuietly(current)
        }
        log.i { "start runtime engine=${identity.engine.value} source=${identity.source.value}" }
        val created = adapterCall(log, "createRuntime") {
            withContext(context.io) { resolved.registration.factory.value.createRuntime(identity) }
        }
        if (created.identity != identity) {
            log.e { "runtime reported another identity engine=${identity.engine.value}" }
            closeQuietly(created)
            fail(EngineFailure.Unknown())
        }
        runtimes[key] = created
        created
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
