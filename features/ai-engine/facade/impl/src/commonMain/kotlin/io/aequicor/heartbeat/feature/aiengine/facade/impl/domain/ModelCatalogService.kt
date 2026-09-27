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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Last successful discovery for one engine and binding. */
@Serializable
data class CachedModels(
    val engine: EngineId,
    val binding: EngineBindingId,
    val models: List<ModelInfo>,
    val checkedAt: Instant,
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
 * [ModelCatalog] with a persistent cache. Discovery runs only on [refresh] through the exact binding's route;
 * a failure is thrown and leaves the previous cache intact. Entries older than [freshFor] are reported stale.
 */
class ModelCatalogService(
    private val cache: ModelCache,
    private val routes: RouteResolver,
    private val context: FacadeContext,
    private val freshFor: Duration = 1.days,
) : ModelCatalog {
    private val log = Log.tag("ModelCatalog")
    private val mutex = Mutex()

    override fun observe(engine: EngineId, binding: EngineBindingId): StateFlow<ModelCatalogSnapshot> = cache.observe()
        .map { entries -> snapshot(entries.find(engine, binding)) }
        .stateIn(context.scope, SharingStarted.WhileSubscribed(), snapshot(null))

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
        val entry = CachedModels(engine, binding, models, context.clock.now())
        mutex.withLock { cache.save(cache.load().filterNot { it.engine == engine && it.binding == binding } + entry) }
        log.i { "models cached engine=${engine.value} count=${models.size}" }
        return snapshot(entry)
    }

    private fun snapshot(entry: CachedModels?): ModelCatalogSnapshot = if (entry == null) {
        ModelCatalogSnapshot(emptyList(), Observation())
    } else {
        val isStale = context.clock.now() - entry.checkedAt > freshFor
        ModelCatalogSnapshot(entry.models, Observation(entry.checkedAt, isStale))
    }

    private fun List<CachedModels>.find(engine: EngineId, binding: EngineBindingId) =
        firstOrNull { it.engine == engine && it.binding == binding }
}
