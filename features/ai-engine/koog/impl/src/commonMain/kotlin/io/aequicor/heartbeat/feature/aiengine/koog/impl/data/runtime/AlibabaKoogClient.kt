package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionStreamResponse
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Qwen includes empty text alongside streaming tool arguments. Koog 1.3 treats even empty text as a boundary
 * and prematurely finishes the pending tool call. Drop only these empty text fields before SDK decoding;
 * nonempty text, tool fragments, reasoning, finish reasons and usage retain their original meaning.
 */
internal class AlibabaKoogClient(
    apiKey: String,
    settings: OpenAIClientSettings,
    httpClientFactory: KoogHttpClient.Factory,
) : OpenAILLMClient(apiKey, settings, httpClientFactory) {
    private val log = Log.tag("KoogAlibaba")

    override fun decodeStreamingResponse(data: String): OpenAIChatCompletionStreamResponse {
        log.d { "Decoding Qwen stream frame" }
        return super.decodeStreamingResponse(normalizeQwenStreamChunk(data))
    }
}

internal fun normalizeQwenStreamChunk(data: String): String {
    val response = Json.parseToJsonElement(data) as? JsonObject ?: return data
    val choices = response["choices"] as? JsonArray ?: return data
    var hasChanged = false
    val normalized = choices.map { element ->
        val choice = element as? JsonObject ?: return@map element
        val delta = choice["delta"] as? JsonObject ?: return@map element
        if (delta["content"] != JsonPrimitive("")) return@map element
        hasChanged = true
        JsonObject(choice + ("delta" to JsonObject(delta - "content")))
    }
    return if (hasChanged) JsonObject(response + ("choices" to JsonArray(normalized))).toString() else data
}
