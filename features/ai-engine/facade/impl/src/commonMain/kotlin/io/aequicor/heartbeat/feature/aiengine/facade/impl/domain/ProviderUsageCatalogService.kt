package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.withoutLabel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReportsProviderUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/** Shared usage gate, read by the catalog without depending on toggle storage. */
interface EngineUsageGate {
    /** Current value and subsequent changes. */
    fun observe(): Flow<Boolean>

    /** Current value, including before observation starts. */
    suspend fun isEnabled(): Boolean
}

/**
 * Memory-only provider telemetry. Reads never start runtimes; explicit requests share one profile-owned
 * refresh per runtime and source context for thirty seconds. Account changes discard old observations and collectors.
 */
@Suppress("LongParameterList") // These profile services own distinct route, runtime and telemetry gates.
class ProviderUsageCatalogService(
    private val routes: RouteResolver,
    private val pool: RuntimePool,
    private val enabled: EnabledEngines,
    private val sources: AuthSources,
    private val gate: EngineUsageGate,
    private val context: FacadeContext,
) : ProviderUsageCatalog {
    private val log = Log.tag("ProviderUsage")
    private val mutex = Mutex()
    private val entries = mutableMapOf<UsageIdentity, UsageEntry>()
    private val snapshots = MutableStateFlow(emptyMap<UsageIdentity, ProviderUsageSnapshot>())
    private val eligible = combine(
        enabled.state,
        routes.saved,
        sources.state,
        gate.observe(),
    ) { engines, bindings, auth, isOn ->
        if (!isOn) {
            emptyMap()
        } else {
            bindings.mapNotNull { binding ->
                val source = auth.firstOrNull { it.info.id == binding.authSource }
                val registration = enabled.registry.find(binding.engine)
                val isBound = binding.isEnabled && binding.engine in engines
                val isSupported = registration?.let {
                    enabled.registry.supportsPlatform(it) && ReportsProviderUsage.id in it.descriptor.declaredFeatures
                } ?: false
                if (isBound && isSupported && source != null) {
                    (binding.engine to binding.id) to UsageIdentity(
                        RuntimeIdentity(binding.engine, source.info.id, source.info.revision),
                        source.withoutLabel(),
                    )
                } else {
                    null
                }
            }.toMap()
        }
    }.stateIn(context.scope, SharingStarted.Eagerly, emptyMap())

    init {
        context.scope.launch {
            eligible.drop(1).collect { current ->
                val valid = current.values.toSet()
                mutex.withLock {
                    val retired = entries.keys.filter { it !in valid }
                    retired.forEach { identity ->
                        entries.remove(identity)?.let { entry ->
                            entry.observation?.cancel()
                            entry.request?.cancel()
                        }
                    }
                    snapshots.update { cache -> cache.filterKeys { it in valid } }
                    if (retired.isNotEmpty()) log.d { "Discarded provider observations count=${retired.size}" }
                }
            }
        }
    }

    override fun observe(engine: EngineId, binding: EngineBindingId): Flow<ProviderUsageSnapshot> =
        combine(eligible, snapshots) { current, cache ->
            current[engine to binding]?.let(cache::get) ?: ProviderUsageSnapshot()
        }.distinctUntilChanged()

    override suspend fun refresh(engine: EngineId, binding: EngineBindingId): ProviderUsageSnapshot {
        if (!gate.isEnabled()) fail(OperationNotAllowed)
        if (enabled.registry.find(engine)?.descriptor?.declaredFeatures?.contains(ReportsProviderUsage.id) != true) {
            return ProviderUsageSnapshot()
        }
        val resolved = routes.resolveSession(engine, binding)
        val identity = resolved.usageIdentity()
        val request = mutex.withLock {
            val entry = entries.getOrPut(identity) { UsageEntry() }
            val previous = entry.request
            val isRecent = entry.requestedAt?.let { context.clock.now() - it < REFRESH_INTERVAL } ?: false
            if (previous != null && (!previous.isCompleted || isRecent)) {
                previous
            } else {
                entry.requestedAt = context.clock.now()
                context.scope.async(start = CoroutineStart.LAZY) { refreshResult(resolved, entry) }
                    .also { entry.request = it }
            }
        }
        request.start()
        request.await().getOrThrow()
        return snapshots.value[identity] ?: ProviderUsageSnapshot()
    }

    private suspend fun refreshResult(resolved: ResolvedRoute, entry: UsageEntry): Result<ProviderUsageSnapshot> = try {
        Result.success(refresh(resolved, entry))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun refresh(resolved: ResolvedRoute, entry: UsageEntry): ProviderUsageSnapshot = try {
        val identity = resolved.usageIdentity()
        requireCurrent(resolved)
        val runtime = pool.sessionRuntime(
            resolved,
            TimeSource.Monotonic.markNow() + 30.seconds,
            recheck = {
                requireCurrent(resolved)
                routes.recheckSession(resolved)
            },
            prepare = { routes.bindAdmitted(resolved) },
        )
        val feature = runtime.features.resolve(ReportsProviderUsage).orFail()
        observeRuntime(identity, runtime, feature, entry)
        log.i { "Refresh provider limits engine=${resolved.identity.engine.value}" }
        val snapshot = adapterCall(log, "providerUsage") { withContext(context.io) { feature.refresh() } }
        requireCurrent(resolved)
        publish(identity, entry, snapshot)
        snapshot
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Provider limits refresh failed engine=${resolved.identity.engine.value}" }
        mutex.withLock {
            val identity = resolved.usageIdentity()
            if (entries[identity] === entry) {
                snapshots.update { cache ->
                    val previous = cache[identity]
                    if (previous == null || (e as? EngineException)?.failure is EngineFailure.Authentication) {
                        cache - identity
                    } else {
                        val stale = previous.copy(observation = previous.observation.copy(isStale = true))
                        cache + (identity to stale)
                    }
                }
            }
        }
        throw e
    }

    private suspend fun requireCurrent(resolved: ResolvedRoute) {
        val binding = routes.savedNow().firstOrNull { it.id == resolved.binding.id }
        val source = sources.get(resolved.identity.source)
        val isEnabled = gate.isEnabled() && resolved.identity.engine in enabled.current()
        val isSameRoute = binding == resolved.binding && source?.withoutLabel() == resolved.source.withoutLabel()
        if (!isEnabled || !isSameRoute) {
            fail(OperationNotAllowed)
        }
    }

    private suspend fun observeRuntime(
        identity: UsageIdentity,
        runtime: EngineRuntime,
        feature: ReportsProviderUsage,
        entry: UsageEntry,
    ) = mutex.withLock {
        if (entries[identity] !== entry || entry.runtime === runtime) return@withLock
        entry.observation?.cancel()
        entry.runtime = runtime
        entry.observation = context.scope.launch {
            feature.state.collect { snapshot -> publish(identity, entry, snapshot) }
        }
    }

    private suspend fun publish(identity: UsageIdentity, entry: UsageEntry, snapshot: ProviderUsageSnapshot) {
        mutex.withLock {
            // Saved routes may resolve before their eager observation hydrates. Keep the first native snapshot;
            // observe() masks it until the exact identity is eligible, and invalidation retires the entry.
            if (entries[identity] === entry) {
                log.d { "Provider limits updated engine=${identity.runtime.engine.value}" }
                snapshots.update { it + (identity to snapshot) }
            }
        }
    }

    private class UsageEntry {
        var requestedAt: Instant? = null
        var request: Deferred<Result<ProviderUsageSnapshot>>? = null
        var runtime: EngineRuntime? = null
        var observation: Job? = null
    }

    /** Non-secret identity of the complete source context; the user-visible label is intentionally excluded. */
    private data class UsageIdentity(val runtime: RuntimeIdentity, val source: AuthSource)

    private fun ResolvedRoute.usageIdentity(): UsageIdentity = UsageIdentity(identity, source.withoutLabel())

    private companion object {
        val REFRESH_INTERVAL = 30.seconds
    }
}
