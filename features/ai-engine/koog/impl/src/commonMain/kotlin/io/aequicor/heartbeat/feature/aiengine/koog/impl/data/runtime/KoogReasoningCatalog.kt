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
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Public model catalog for providers whose API does not report reasoning support. */
internal interface KoogReasoningCatalog {
    /**
     * Effort levels of [model]; null when the catalog does not know the model or is unavailable. An editable route
     * is looked up in the section of the fixed vendor route whose [origin] it repeats, so a compatible connection
     * to a catalog endpoint learns the same levels as the native one.
     */
    suspend fun levels(provider: KoogProvider, origin: EndpointOrigin, model: String): List<String>?

    /** Exact vendor model modalities; compatible endpoints are never looked up in the vendor catalog. */
    suspend fun inputSupport(provider: KoogProvider, model: String): PromptInputSupport? = null
}

/**
 * models.dev catalog (`reasoning_options` of type `effort`), refreshed at most daily and cached in the profile, so
 * an offline start keeps the last snapshot. A failed download is not retried for [RetryInterval], so offline discovery
 * does not hit the network once per model. Only OpenAI and the Alibaba token plan are kept; levels Koog cannot send
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
    private var failedAt: Instant? = null

    override suspend fun levels(provider: KoogProvider, origin: EndpointOrigin, model: String): List<String>? {
        val section = catalogSection(provider, origin) ?: return null
        return current()?.providers?.get(section)?.get(model)
    }

    override suspend fun inputSupport(provider: KoogProvider, model: String): PromptInputSupport? =
        InputCatalogSections[provider]?.let { current()?.inputs?.get(it)?.get(model) }

    private suspend fun current(): CatalogSnapshot? = mutex.withLock {
        val cached = snapshot ?: store.get(Key).also { snapshot = it }
        if (cached != null && clock.now() - cached.fetchedAt < RefreshInterval) return@withLock cached
        val now = clock.now()
        if (failedAt?.let { now - it < RetryInterval } == true) return@withLock cached
        val fresh = fetch()
        if (fresh == null) {
            failedAt = now
            return@withLock cached
        }
        failedAt = null
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
        val RetryInterval = 10.minutes
        const val CATALOG_URL = "https://models.dev/api.json"
    }
}

/**
 * Catalog section of [provider]; an editable route without a section of its own borrows the section of the fixed
 * vendor route whose origin it repeats, so compatible connections to catalog endpoints share their levels.
 */
internal fun catalogSection(provider: KoogProvider, origin: EndpointOrigin): String? = CatalogSections[provider]
    ?: CatalogSections[KoogProvider.entries.firstOrNull { !it.isOriginEditable && it.origin == origin }]

/** Effort levels per catalog section and model id. */
@Serializable
internal data class CatalogSnapshot(
    val fetchedAt: Instant,
    val providers: Map<String, Map<String, List<String>>>,
    val inputs: Map<String, Map<String, PromptInputSupport>> = emptyMap(),
)

internal val CatalogSections = mapOf(
    KoogProvider.OpenAI to "openai",
    KoogProvider.AlibabaQwen to "alibaba-token-plan",
)

private val InputCatalogSections = CatalogSections + (KoogProvider.Anthropic to "anthropic")

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
    val inputs = InputCatalogSections.values.associateWith { section ->
        val models = (root[section] as? JsonObject)?.get("models") as? JsonObject
        models.orEmpty().mapValues { (_, element) -> catalogInputs(element as? JsonObject) }
    }
    return CatalogSnapshot(now, providers, inputs)
}

private fun catalogInputs(model: JsonObject?): PromptInputSupport {
    val modalities = ((model?.get("modalities") as? JsonObject)?.get("input") as? JsonArray)
        .orEmpty().mapNotNull(::text)
    return PromptInputSupport.TextDocuments.copy(
        imageMediaTypes = if ("image" in modalities) {
            setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        } else {
            emptySet()
        },
        resourceMediaTypes = PromptInputSupport.TextDocuments.resourceMediaTypes +
            if ("pdf" in modalities) setOf("application/pdf") else emptySet(),
    )
}

private fun text(element: kotlinx.serialization.json.JsonElement): String? = (element as? JsonPrimitive)?.contentOrNull
