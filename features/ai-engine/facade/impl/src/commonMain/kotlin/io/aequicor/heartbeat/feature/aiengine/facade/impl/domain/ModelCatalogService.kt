package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Last successful discovery for one engine and binding, made by an adapter of catalog [revision]. */
@Serializable
data class CachedModels(
    val engine: EngineId,
    val binding: EngineBindingId,
    val models: List<ModelInfo>,
    val checkedAt: Instant,
    val revision: Int = 0,
)

/** Persistent model cache of one profile. Failures propagate. */
interface ModelCache {
    /** Current entries and their changes. */
    fun observe(): Flow<List<CachedModels>>

    /** Current entries. */
    suspend fun load(): List<CachedModels>

    /** Replaces every entry atomically. */
    suspend fun save(entries: List<CachedModels>)
}

/**
 * [ModelCatalog] with a persistent cache. Discovery runs on [refresh] through the exact binding's route;
 * a failure is thrown and leaves the previous cache intact. Entries older than [freshFor] are reported stale.
 * An observed entry of another adapter catalog revision is reported stale and rediscovered once in the background.
 * A binding removed during discovery gets no cache entry, and entries of removed bindings are hidden and dropped
 * on the next write.
 */
class ModelCatalogService(
    private val cache: ModelCache,
    private val routes: RouteResolver,
    private val context: FacadeContext,
    private val freshFor: Duration = 1.days,
) : ModelCatalog {
    private val log = Log.tag("ModelCatalog")
    private val mutex = Mutex()
    private val upgrades = MutableStateFlow(emptySet<Pair<EngineId, EngineBindingId>>())

    override fun observe(engine: EngineId, binding: EngineBindingId): StateFlow<ModelCatalogSnapshot> =
        combine(cache.observe(), routes.saved) { entries, saved ->
            val isBound = saved.any { it.id == binding && it.engine == engine }
            val entry = if (isBound) entries.find(engine, binding) else null
            if (entry != null && entry.isOutdated()) upgrade(engine, binding)
            snapshot(entry)
        }.stateIn(context.scope, SharingStarted.WhileSubscribed(), snapshot(null))

    override suspend fun refresh(engine: EngineId, binding: EngineBindingId): ModelCatalogSnapshot {
        log.i { "discover models engine=${engine.value} binding=${binding.value}" }
        val resolved = routes.resolve(engine, binding)
        val discovered = try {
            withContext(context.io) {
                resolved.registration.factory.value.discoverModels(resolved.source, resolved.context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            log.w(e) { "model discovery failed engine=${engine.value} failure=${e.failure.code}" }
            throw e
        } catch (e: Exception) {
            log.e(e) { "model discovery crashed engine=${engine.value}" }
            fail(EngineFailure.Unknown())
        }
        val models = discovered.filter { it.target.engine == engine && it.target.binding == binding }
        if (models.size != discovered.size) log.w { "dropped foreign models count=${discovered.size - models.size}" }
        val entry = CachedModels(
            engine,
            binding,
            models,
            context.clock.now(),
            resolved.registration.modelCatalogRevision,
        )
        mutex.withLock {
            val live = routes.savedNow().map { it.engine to it.id }.toSet()
            if ((engine to binding) !in live) {
                log.w { "binding removed during discovery binding=${binding.value}" }
                fail(OperationNotAllowed)
            }
            val kept = cache.load().filter { (it.engine to it.binding) in live && it.binding != binding }
            cache.save(kept + entry)
        }
        log.i { "models cached engine=${engine.value} count=${models.size}" }
        return snapshot(entry)
    }

    /** Rediscovers an outdated entry once per binding for the profile session; a failure keeps the old entry. */
    private fun upgrade(engine: EngineId, binding: EngineBindingId) {
        val key = engine to binding
        if (key in upgrades.getAndUpdate { it + key }) return
        log.i { "model catalog revision changed engine=${engine.value} binding=${binding.value}" }
        context.scope.launch {
            try {
                refresh(engine, binding)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                log.w(e) { "model catalog upgrade failed engine=${engine.value}" }
            }
        }
    }

    private fun CachedModels.isOutdated(): Boolean =
        routes.modelCatalogRevision(engine)?.let { it != revision } ?: false

    private fun snapshot(entry: CachedModels?): ModelCatalogSnapshot = if (entry == null) {
        ModelCatalogSnapshot(emptyList(), Observation())
    } else {
        val isStale = context.clock.now() - entry.checkedAt > freshFor || entry.isOutdated()
        ModelCatalogSnapshot(entry.models, Observation(entry.checkedAt, isStale))
    }

    private fun List<CachedModels>.find(engine: EngineId, binding: EngineBindingId) =
        firstOrNull { it.engine == engine && it.binding == binding }
}
