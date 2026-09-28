package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.networkResult
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Public model catalog for providers whose API does not report reasoning support. */
internal interface KoogReasoningCatalog {
    /** Effort levels of [model]; null when the catalog does not know the model or is unavailable. */
    suspend fun levels(provider: KoogProvider, model: String): List<String>?
}

/**
 * models.dev catalog (`reasoning_options` of type `effort`), refreshed at most daily and cached in the profile, so
 * an offline start keeps the last snapshot. Only OpenAI and the Alibaba token plan are kept; levels Koog cannot send
 * are dropped.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ModelsDevReasoningCatalog(
    private val httpClient: HttpClient,
    @ForScope(ProfileScope::class) stores: DataStores,
    private val clock: Clock,
) : KoogReasoningCatalog {
    private val log = Log.tag("KoogReasoningCatalog")
    private val store by lazy { stores.keyValue(Spec) }
    private val mutex = Mutex()
    private var snapshot: CatalogSnapshot? = null

    override suspend fun levels(provider: KoogProvider, model: String): List<String>? {
        val section = CatalogSections[provider] ?: return null
        return current()?.providers?.get(section)?.get(model)
    }

    private suspend fun current(): CatalogSnapshot? = mutex.withLock {
        val cached = snapshot ?: store.get(Key).also { snapshot = it }
        if (cached != null && clock.now() - cached.fetchedAt < RefreshInterval) return@withLock cached
        val fresh = fetch() ?: return@withLock cached
        store.set(Key, fresh)
        log.i { "reasoning catalog refreshed: ${fresh.providers.values.sumOf { it.size }} models" }
        snapshot = fresh
        fresh
    }

    private suspend fun fetch(): CatalogSnapshot? = networkResult {
        val response = httpClient.get(CATALOG_URL)
        if (!response.status.isSuccess()) {
            log.w { "Reasoning catalog request failed: status=${response.status.value}" }
            return@networkResult null
        }
        try {
            parseCatalog(response.bodyAsText(), clock.now())
        } catch (e: SerializationException) {
            log.w(e) { "Reasoning catalog is not valid JSON" }
            null
        }
    }.getOrElse {
        log.w(it) { "Reasoning catalog unavailable; keeping the cached snapshot" }
        null
    }

    private companion object {
        val Spec = KeyValueSpec("koog_reasoning_catalog")
        val Key = jsonKey("snapshot", CatalogSnapshot.serializer())
        val RefreshInterval = 24.hours
        const val CATALOG_URL = "https://models.dev/api.json"
    }
}

/** Effort levels per catalog section and model id. */
@Serializable
internal data class CatalogSnapshot(val fetchedAt: Instant, val providers: Map<String, Map<String, List<String>>>)

internal val CatalogSections = mapOf(
    KoogProvider.OpenAI to "openai",
    KoogProvider.AlibabaQwen to "alibaba-token-plan",
)

private val CatalogJson = Json { ignoreUnknownKeys = true }

/** Keeps only `effort` options Koog can send as `reasoning_effort`; null when the text is not a catalog object. */
internal fun parseCatalog(text: String, now: Instant): CatalogSnapshot? {
    val root = CatalogJson.parseToJsonElement(text) as? JsonObject ?: return null
    val providers = CatalogSections.values.associateWith { section ->
        val models = (root[section] as? JsonObject)?.get("models") as? JsonObject
        models.orEmpty().entries.asSequence().map { (id, element) ->
            val options = ((element as? JsonObject)?.get("reasoning_options") as? JsonArray).orEmpty()
            val effort = options.firstOrNull { (it as? JsonObject)?.get("type")?.let(::text) == "effort" }
            val advertised = ((effort as? JsonObject)?.get("values") as? JsonArray).orEmpty().mapNotNull(::text)
            val values = KoogReasoningEffortLevels.filter { it in advertised }
            id to values
        }.toMap()
    }
    return CatalogSnapshot(now, providers)
}

private fun text(element: kotlinx.serialization.json.JsonElement): String? = (element as? JsonPrimitive)?.contentOrNull
