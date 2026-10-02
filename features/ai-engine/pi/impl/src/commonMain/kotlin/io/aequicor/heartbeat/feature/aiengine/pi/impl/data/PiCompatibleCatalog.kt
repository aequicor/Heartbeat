package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Model advertised by a compatible server. */
internal data class PiCompatibleModel(val id: String, val name: String?)

/**
 * Lists models of an OpenAI-/Anthropic-compatible server (`GET <base>/models`, Anthropic `<base>/v1/models`).
 * Pi requires custom providers to name their models, so the list is fetched before each process start.
 * Redirects are never followed with the key.
 */
@Inject
internal class PiCompatibleCatalog(private val httpClient: HttpClient) {
    private val log = Log.tag("PiCompatibleCatalog")

    suspend fun models(
        protocol: CompatibleProtocol,
        scope: AuthScope,
        key: String,
        source: AuthSourceId,
    ): List<PiCompatibleModel> {
        require(CompatibleProtocol.of(scope) == protocol) { "Scope not allowed" }
        val base = scope.origin.value + protocol.apiBase(scope)
        val url = when (protocol) {
            CompatibleProtocol.OpenAI -> "$base/models"
            CompatibleProtocol.Anthropic -> "$base/v1/models"
        }
        log.i { "Listing models of a ${protocol.name}-compatible server" }
        val client = httpClient.config { followRedirects = false }
        val text = try {
            client.use {
                val response = it.get(url) {
                    when (protocol) {
                        CompatibleProtocol.OpenAI -> header(HttpHeaders.Authorization, "Bearer $key")

                        CompatibleProtocol.Anthropic -> {
                            header("x-api-key", key)
                            header("anthropic-version", ANTHROPIC_VERSION)
                        }
                    }
                }
                checkStatus(response.status, source)
                response.bodyAsText()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log.w(e) { "Compatible server is unreachable" }
            piFailure(EngineFailure.Transport(TransportFailureReason.NetworkUnavailable))
        } catch (e: IllegalArgumentException) {
            // Unresolvable hosts surface as UnresolvedAddressException, an IllegalArgumentException on the JVM.
            log.w(e) { "Compatible server is unreachable" }
            piFailure(EngineFailure.Transport(TransportFailureReason.NetworkUnavailable))
        }
        return try {
            parseCompatibleModels(text)
        } catch (e: SerializationException) {
            log.w(e) { "Compatible server returned a malformed model list" }
            piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Compatible server returned a malformed model list" }
            piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
        }
    }
}

private fun checkStatus(status: HttpStatusCode, source: AuthSourceId) {
    if (status.isSuccess()) return
    Log.tag("PiCompatibleCatalog").w { "Compatible server answered ${status.value}" }
    if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
        authenticationFailure(AuthFailureReason.NotAuthenticated, source)
    }
    piFailure(EngineFailure.Transport(TransportFailureReason.ServiceUnavailable))
}

/** Both protocols answer `{"data":[{"id":…}]}`; Anthropic adds `display_name`. */
internal fun parseCompatibleModels(text: String): List<PiCompatibleModel> {
    val data = Json.parseToJsonElement(text).jsonObject["data"] as? JsonArray
        ?: throw IllegalArgumentException("Missing data")
    return data.mapNotNull { element ->
        val model = element as? JsonObject ?: return@mapNotNull null
        val id = (model["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        PiCompatibleModel(id, (model["display_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
    }.distinctBy { it.id }
}

/**
 * Pi `models.json` declaring [provider] at the API base of [scope]. The key is never written: Pi interpolates
 * [COMPATIBLE_KEY_VARIABLE] from the process environment. A model Pi's own catalog serves at the same API base
 * ([builtin], see [piBuiltinModelsAt]) inherits its reasoning, thinking levels, compatibility and limits: an
 * undeclared model is not a reasoning model for Pi, so it would offer no thinking levels.
 */
internal fun piModelsJson(
    provider: PiProvider,
    scope: AuthScope,
    models: List<PiCompatibleModel>,
    builtin: Map<String, JsonObject> = emptyMap(),
): String {
    val protocol = requireNotNull(provider.compatible)
    return buildJsonObject {
        put(
            "providers",
            buildJsonObject {
                put(
                    provider.id,
                    buildJsonObject {
                        put("baseUrl", piCompatibleBaseUrl(protocol, scope))
                        put("api", protocol.piApi())
                        put("apiKey", "\$$COMPATIBLE_KEY_VARIABLE")
                        put(
                            "models",
                            buildJsonArray {
                                models.forEach { model ->
                                    add(
                                        buildJsonObject {
                                            put("id", model.id)
                                            model.name?.let { put("name", it) }
                                            builtin[model.id]?.let(::putInherited)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
        )
    }.toString()
}

/** API base Pi calls for a compatible [scope]. */
internal fun piCompatibleBaseUrl(protocol: CompatibleProtocol, scope: AuthScope): String =
    scope.origin.value + protocol.apiBase(scope)

/** Pi `api` of a compatible [CompatibleProtocol]. */
internal fun CompatibleProtocol.piApi(): String = when (this) {
    CompatibleProtocol.OpenAI -> "openai-completions"
    CompatibleProtocol.Anthropic -> "anthropic-messages"
}

/**
 * Models of Pi's own catalog (`get_available_models` objects) served by [api] at [baseUrl], by model id.
 * Trailing slashes are ignored; the first provider declaring a model wins.
 */
internal fun piBuiltinModelsAt(catalog: List<JsonObject>, api: String, baseUrl: String): Map<String, JsonObject> {
    val base = baseUrl.trimEnd('/')
    return catalog.asSequence()
        .filter { it.text("api") == api && it.text("baseUrl")?.trimEnd('/') == base }
        .mapNotNull { model -> model.text("id")?.let { it to model } }
        .distinctBy { it.first }
        .toMap()
}

/**
 * `models.json` of a catalog probe: Pi lists a built-in provider only when it has a key, so each provider of
 * [PiCatalogProviders] gets a placeholder that is never sent anywhere.
 */
internal fun piCatalogProbeJson(): String = buildJsonObject {
    put(
        "providers",
        buildJsonObject {
            PiCatalogProviders.forEach { id -> put(id, buildJsonObject { put("apiKey", CATALOG_PROBE_KEY) }) }
        },
    )
}.toString()

/**
 * Built-in Pi providers with a fixed OpenAI-/Anthropic-compatible API base, which a compatible route may target
 * directly. Unknown ids are ignored by Pi, so the list may lag behind the bundled version without harm.
 */
internal val PiCatalogProviders = listOf(
    "baseten", "cerebras", "groq", "huggingface", "kimi-coding", "minimax", "minimax-cn", "moonshotai",
    "moonshotai-cn", "nvidia", "opencode", "opencode-go", "openrouter", "qwen-token-plan", "qwen-token-plan-cn",
    "qwen-token-plan-individual", "together", "xiaomi", "xiaomi-token-plan-ams", "xiaomi-token-plan-cn",
    "xiaomi-token-plan-sgp", "zai", "zai-coding-cn",
)

/** Model fields a compatible model inherits from Pi's catalog; identity, name and cost stay the server's. */
private val PiInheritedFields = listOf("reasoning", "thinkingLevelMap", "compat", "contextWindow", "maxTokens", "input")

private fun JsonObjectBuilder.putInherited(known: JsonObject) {
    PiInheritedFields.forEach { field -> known[field]?.let { put(field, it) } }
}

private const val CATALOG_PROBE_KEY = "heartbeat-catalog-probe"

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private const val ANTHROPIC_VERSION = "2023-06-01"

/** Exact route limits explicitly written to models.json, before Pi supplies defaults for missing fields. */
internal fun piConfiguredContextWindows(modelsJson: String?): Map<String, Long> {
    if (modelsJson == null) return emptyMap()
    val providers = Json.parseToJsonElement(modelsJson).jsonObject["providers"] as? JsonObject ?: return emptyMap()
    return buildMap {
        providers.forEach { (provider, config) ->
            val models = (config as? JsonObject)?.get("models") as? JsonArray
            models.orEmpty().forEach modelEntry@{ element ->
                val model = element as? JsonObject ?: return@modelEntry
                val id = model.text("id") ?: return@modelEntry
                val capacity = (model["contextWindow"] as? JsonPrimitive)?.longOrNull
                if (capacity != null && capacity > 0) put("$provider/$id", capacity)
            }
        }
    }
}
