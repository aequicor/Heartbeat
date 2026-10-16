package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.research

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.networkResult
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelFacts
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection.normalizedModel
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Public facts about models: limits, list prices and input kinds. */
internal fun interface ModelFactsSource {
    /** Facts of [model], matched exactly, then by its normalized id; null when unknown or unavailable. */
    suspend fun facts(model: ModelId): ModelFacts?
}

/**
 * Facts from the models.dev catalog, read only when a description is researched: kept in memory for a day, and a
 * failed download is not retried for ten minutes. Catalog sections (providers) are searched in order; the first
 * model with an exact id wins, then the first with the same normalized id.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ModelsDevFacts(private val httpClient: HttpClient, private val clock: Clock) : ModelFactsSource {
    private val log = Log.tag("ModelsDevFacts")
    private val mutex = Mutex()
    private var index: FactsIndex? = null
    private var failedAt: Instant? = null

    override suspend fun facts(model: ModelId): ModelFacts? {
        val current = current() ?: return null
        return current.exact[model.value] ?: current.normalized[normalizedModel(model)]
    }

    private suspend fun current(): FactsIndex? = mutex.withLock {
        val now = clock.now()
        val cached = index
        val isFresh = cached != null && now - cached.fetchedAt < REFRESH
        val isBackingOff = failedAt?.let { now - it < RETRY } == true
        if (isFresh || isBackingOff) return@withLock cached
        val fresh = fetch(now)
        if (fresh == null) {
            failedAt = now
            cached
        } else {
            failedAt = null
            index = fresh
            log.i { "models.dev facts refreshed: ${fresh.exact.size} models" }
            fresh
        }
    }

    private suspend fun fetch(now: Instant): FactsIndex? = networkResult {
        val response = httpClient.get(CATALOG_URL)
        if (!response.status.isSuccess()) {
            log.w { "models.dev request failed: status=${response.status.value}" }
            return@networkResult null
        }
        try {
            parseModelsDev(response.bodyAsText(), now)
        } catch (e: SerializationException) {
            log.w(e) { "models.dev catalog is not valid JSON" }
            null
        }
    }.getOrElse {
        log.w(it) { "models.dev unavailable; descriptions are researched without catalog facts" }
        null
    }

    private companion object {
        const val CATALOG_URL = "https://models.dev/api.json"
        val REFRESH = 24.hours
        val RETRY = 10.minutes
    }
}

/** Facts by exact model id and by normalized id. */
internal data class FactsIndex(
    val fetchedAt: Instant,
    val exact: Map<String, ModelFacts>,
    val normalized: Map<String, ModelFacts>,
)

/** Indexes every model of every provider section; null when the text is not a catalog object. */
internal fun parseModelsDev(text: String, now: Instant): FactsIndex? {
    val root = CatalogJson.parseToJsonElement(text) as? JsonObject ?: return null
    val exact = linkedMapOf<String, ModelFacts>()
    val normalized = linkedMapOf<String, ModelFacts>()
    root.values.forEach { provider ->
        val models = ((provider as? JsonObject)?.get("models") as? JsonObject).orEmpty()
        models.forEach { (id, element) ->
            val facts = (element as? JsonObject)?.let { factsOf(it, now) } ?: return@forEach
            exact.getOrPut(id) { facts }
            normalized.getOrPut(normalizedModel(ModelId(id))) { facts }
        }
    }
    return FactsIndex(now, exact, normalized)
}

private fun factsOf(model: JsonObject, now: Instant): ModelFacts {
    val cost = model["cost"] as? JsonObject
    val limit = model["limit"] as? JsonObject
    val inputs = ((model["modalities"] as? JsonObject)?.get("input") as? JsonArray).orEmpty().mapNotNull(::text)
    return ModelFacts(
        contextTokens = limit?.get("context")?.let(::long),
        outputTokens = limit?.get("output")?.let(::long),
        inputUsdPerMTok = cost?.get("input")?.let(::double),
        outputUsdPerMTok = cost?.get("output")?.let(::double),
        modalities = inputs.toSet(),
        releaseDate = model["release_date"]?.let(::text),
        source = "models.dev",
        fetchedAt = now,
    )
}

private fun text(element: JsonElement): String? = (element as? JsonPrimitive)?.contentOrNull
private fun long(element: JsonElement): Long? = (element as? JsonPrimitive)?.longOrNull
private fun double(element: JsonElement): Double? = (element as? JsonPrimitive)?.doubleOrNull

private val CatalogJson = Json { ignoreUnknownKeys = true }
