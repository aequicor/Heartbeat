package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClient
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.reflect.KClass

/**
 * Koog 1.3's streaming Ollama decoder silently skips errors and discards done/done_reason. Strict checks use its
 * single non-streaming execute instead: the SDK must successfully decode the whole response DTO. This observer
 * additionally requires an explicit boolean done and normal stop on that same response, never EOF as evidence.
 * Only the terminal reason is retained; model content continues through the existing SDK decoder.
 */
internal class IsolatedOllamaCompletion {
    var reason: String? = null
        private set

    fun inspect(body: String) {
        val value = Json.parseToJsonElement(body) as? JsonObject ?: incomplete()
        if (value["error"] != null) incomplete()
        val message = value["message"] as? JsonObject ?: incomplete()
        val role = message["role"] as? JsonPrimitive ?: incomplete()
        if (!role.isString || role.content != "assistant") incomplete()
        val done = value["done"] as? JsonPrimitive ?: incomplete()
        if (done.isString || done.booleanOrNull != true) incomplete()
        val terminal = value["done_reason"] as? JsonPrimitive ?: incomplete()
        if (!terminal.isString || terminal.content != "stop") incomplete()
        reason = terminal.content
    }
}

internal class IsolatedOllamaHttp(
    private val delegate: KoogHttpClient,
    private val completion: IsolatedOllamaCompletion,
) : KoogHttpClient by delegate {
    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        val response = delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)
        completion.inspect(response as? String ?: incomplete())
        return response
    }
}

private fun incomplete(): Nothing = throw IsolatedKoogRefusal(IsolatedPromptFailure.Incomplete)
