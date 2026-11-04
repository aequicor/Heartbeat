package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/** Validates the same fields as Koog 1.3 before its decoder can silently discard an invalid line. */
internal class OllamaStreamTerminal {
    var reason: String? = null
}

internal class StrictOllamaStream(private val delegate: KoogHttpClient, private val terminal: OllamaStreamTerminal) :
    KoogHttpClient by delegate {

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = flow {
        terminal.reason = null
        var isEnded = false
        delegate.lines(path, requestBody, requestBodyType, parameters, headers).collect { line ->
            check(!isEnded) { "Ollama stream continued after its terminal response" }
            val chunk = strictJson.decodeFromString(serializer<OllamaStreamChunk>(), line)
            check(chunk.error == null) { "Ollama stream returned an error" }
            if (chunk.isDone) {
                check(chunk.doneReason == "stop") { "Ollama stream did not finish normally" }
                isEnded = true
            }
            emit(line)
        }
        check(isEnded) { "Ollama stream ended without a terminal response" }
        terminal.reason = "stop"
    }
}

private val strictJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class OllamaStreamChunk(
    val model: String,
    val message: OllamaStreamMessage? = null,
    @SerialName("done") val isDone: Boolean,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    val error: String? = null,
)

@Serializable
private data class OllamaStreamMessage(
    val role: String,
    val content: String,
    val thinking: String? = null,
    val images: List<String>? = null,
    @SerialName("tool_calls") val toolCalls: List<OllamaStreamToolCall>? = null,
)

@Serializable
private data class OllamaStreamToolCall(val function: OllamaStreamFunction)

@Serializable
private data class OllamaStreamFunction(val name: String, val arguments: JsonElement)
