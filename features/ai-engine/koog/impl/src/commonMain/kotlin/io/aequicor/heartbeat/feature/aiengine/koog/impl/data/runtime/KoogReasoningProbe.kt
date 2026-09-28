package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.networkResult
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private val log = Log.tag("KoogReasoningProbe")
private val ProbeJson = Json { ignoreUnknownKeys = true }

/**
 * Reasoning levels the provider API itself reports for [models]; null when the provider has no such field
 * (OpenAI-compatible routes) or the request failed, so callers fall back to the catalog. Keys are never logged.
 */
internal suspend fun probeReasoning(
    client: HttpClient,
    provider: KoogProvider,
    key: String?,
    models: List<String>,
): Map<String, List<String>>? = when (provider) {
    KoogProvider.Anthropic -> anthropicThinking(client, requireNotNull(key))

    KoogProvider.Ollama -> ollamaThinking(client, models)

    // Compatible servers: the Anthropic probe targets the fixed Anthropic origin and must not receive their keys.
    KoogProvider.OpenAI, KoogProvider.AlibabaQwen, KoogProvider.OpenAICompatible, KoogProvider.AnthropicCompatible ->
        null
}

/** `GET /v1/models` lists `capabilities.thinking.types.enabled`, which the budget levels require. */
private suspend fun anthropicThinking(client: HttpClient, key: String): Map<String, List<String>>? = networkResult {
    val response = client.get("${KoogProvider.Anthropic.origin.value}/v1/models?limit=$ANTHROPIC_PAGE") {
        header("x-api-key", key)
        header("anthropic-version", ANTHROPIC_VERSION)
    }
    if (!response.status.isSuccess()) return@networkResult rejected(response.status.value)
    val data = parse(response.bodyAsText()) ?: return@networkResult null
    (data["data"] as? JsonArray).orEmpty().mapNotNull { element ->
        val model = element as? JsonObject ?: return@mapNotNull null
        val id = model.string("id") ?: return@mapNotNull null
        // Models without a capabilities object are left to the catalog instead of being marked unsupported.
        val capabilities = model["capabilities"] as? JsonObject ?: return@mapNotNull null
        val isEnabled = capabilities.path("thinking", "types", "enabled").flag("supported")
        id to if (isEnabled) KoogBudgetLevels else emptyList()
    }.toMap()
}.getOrElse {
    log.w(it) { "Anthropic model capabilities unavailable" }
    null
}

/** `POST /api/show` lists `thinking` among model capabilities; Ollama accepts only on/off. */
private suspend fun ollamaThinking(client: HttpClient, models: List<String>): Map<String, List<String>>? =
    networkResult {
        val levels = models.associateWith { id ->
            val response = client.post("${KoogProvider.Ollama.origin.value}/api/show") {
                contentType(ContentType.Application.Json)
                setBody(JsonObject(mapOf("model" to JsonPrimitive(id))).toString())
            }
            if (!response.status.isSuccess()) return@networkResult rejected(response.status.value)
            val body = parse(response.bodyAsText()) ?: return@networkResult null
            val capabilities = (body["capabilities"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if ("thinking" in capabilities) KoogToggleLevels else emptyList()
        }
        levels
    }.getOrElse {
        log.w(it) { "Ollama model capabilities unavailable" }
        null
    }

private fun rejected(status: Int): Nothing? {
    log.w { "Provider capabilities request failed: status=$status" }
    return null
}

private fun parse(text: String): JsonObject? = try {
    ProbeJson.parseToJsonElement(text) as? JsonObject
} catch (e: SerializationException) {
    log.w(e) { "Provider capabilities response is not JSON" }
    null
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.path(vararg keys: String): JsonObject? =
    keys.fold<String, JsonObject?>(this) { node, key -> node?.get(key) as? JsonObject }

private fun JsonObject?.flag(key: String): Boolean = (this?.get(key) as? JsonPrimitive)?.booleanOrNull == true

private const val ANTHROPIC_PAGE = 1000
private const val ANTHROPIC_VERSION = "2023-06-01"
