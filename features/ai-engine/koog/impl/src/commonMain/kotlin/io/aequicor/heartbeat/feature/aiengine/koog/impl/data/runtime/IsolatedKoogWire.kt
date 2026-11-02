package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.TimeMark

/** A single fresh SDK request; source/deadline checks run again immediately before network dispatch. */
internal data class IsolatedKoogRequest(
    val provider: KoogProvider,
    val model: String,
    val text: String,
    val maxOutputTokens: Int,
    val deadline: TimeMark,
    val lease: OwnedKoogHttpLease = OwnedKoogHttpLease(),
    val beforeDispatch: suspend () -> Unit,
) {
    init {
        require(maxOutputTokens in 1..MAX_OUTPUT_TOKENS) { "Isolated output token budget is out of range" }
    }

    override fun toString(): String = "IsolatedKoogRequest(maxOutputTokens=$maxOutputTokens)"
}

/** Safe typed refusal; never carries SDK payloads, credentials or provider exception text. */
internal class IsolatedKoogRefusal(val reason: IsolatedPromptFailure) : Exception(reason.name)

/**
 * The same SDK and injected application transport remain in use. This boundary checks the serialized body,
 * fixes pinned SDK token mappings and admits one POST only, including Ktor retries/SSE reconnect attempts.
 * The mutex is shared across derived SDK clients of this lease and is intentionally never unlocked.
 */
internal fun isolatedKoogWire(spec: IsolatedKoogRequest) = run {
    val dispatched = Mutex()
    createClientPlugin("IsolatedKoogWire") {
        on(Send) { request ->
            if (!dispatched.tryLock()) refuse(IsolatedPromptFailure.Incomplete)
            if (request.method != HttpMethod.Post) refuse(IsolatedPromptFailure.IsolationUnproven)
            val body = serializedBody(request.body)
            val json = Json.parseToJsonElement(body.content.bytes().decodeToString()) as? JsonObject
                ?: refuse(IsolatedPromptFailure.TokenLimitUnproven)
            val bounded = boundedKoogRequest(json, spec)
            spec.beforeDispatch()
            currentCoroutineContext().ensureActive()
            if (spec.deadline.hasPassedNow()) refuse(IsolatedPromptFailure.Timeout)
            request.setBody(body.bounded(bounded))
            request.headers.remove(HttpHeaders.ContentLength)
            proceed(request)
        }
    }
}

/** Preserve the public ContentWrapper contract used by Ktor SSE; never depend on its internal SSE class. */
private class SerializedKoogBody(
    val content: OutgoingContent.ByteArrayContent,
    val wrappers: List<OutgoingContent.ContentWrapper>,
) {
    fun bounded(json: JsonObject): OutgoingContent = wrappers.asReversed().fold(
        TextContent(json.toString(), ContentType.Application.Json) as OutgoingContent,
    ) { body, wrapper -> wrapper.copy(body) }
}

private fun serializedBody(original: Any): SerializedKoogBody {
    var body = original as? OutgoingContent ?: refuse(IsolatedPromptFailure.TokenLimitUnproven)
    val wrappers = mutableListOf<OutgoingContent.ContentWrapper>()
    while (body is OutgoingContent.ContentWrapper) {
        if (wrappers.size >= MAX_CONTENT_WRAPPERS) refuse(IsolatedPromptFailure.TokenLimitUnproven)
        wrappers += body
        body = body.delegate()
    }
    val content = body as? OutgoingContent.ByteArrayContent ?: refuse(IsolatedPromptFailure.TokenLimitUnproven)
    return SerializedKoogBody(content, wrappers)
}

private fun boundedKoogRequest(body: JsonObject, spec: IsolatedKoogRequest): JsonObject {
    validateKoogContext(body, spec)
    val clean = body.toMutableMap().apply {
        remove("tools")
        remove("system")
        FORBIDDEN_FIELDS.forEach(::remove)
    }
    val tokens = JsonPrimitive(spec.maxOutputTokens)
    when (spec.provider) {
        KoogProvider.OpenAI -> {
            clean.remove("max_tokens")
            clean["max_completion_tokens"] = tokens
            clean["n"] = JsonPrimitive(1)
        }

        KoogProvider.OpenAICompatible, KoogProvider.AlibabaQwen -> {
            clean.remove("max_completion_tokens")
            clean["max_tokens"] = tokens
            clean["n"] = JsonPrimitive(1)
        }

        KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> clean["max_tokens"] = tokens

        KoogProvider.Ollama -> {
            // Koog 1.3 ignores LLMParams.maxTokens in extractOllamaOptions; preserve SDK options and set the cap.
            val options = (clean["options"] as? JsonObject).orEmpty()
            clean["options"] = JsonObject(options + ("num_predict" to tokens))
            clean["think"] = JsonPrimitive(false)
        }
    }
    return JsonObject(clean)
}

private fun validateKoogContext(body: JsonObject, spec: IsolatedKoogRequest) {
    if ((body["model"] as? JsonPrimitive)?.contentOrNull != spec.model) {
        refuse(IsolatedPromptFailure.IsolationUnproven)
    }
    val messages = body["messages"] as? JsonArray ?: refuse(IsolatedPromptFailure.IsolationUnproven)
    val message = messages.singleOrNull() as? JsonObject ?: refuse(IsolatedPromptFailure.IsolationUnproven)
    if ((message["role"] as? JsonPrimitive)?.contentOrNull != "user" || plainText(message["content"]) != spec.text) {
        refuse(IsolatedPromptFailure.IsolationUnproven)
    }
    val forbidden = FORBIDDEN_FIELDS + setOf("tools", "system")
    if (forbidden.any { hasValue(body[it]) }) refuse(IsolatedPromptFailure.IsolationUnproven)
}

private fun hasValue(value: kotlinx.serialization.json.JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonArray -> value.isNotEmpty()
    is JsonObject, is JsonPrimitive -> true
}

private fun plainText(content: kotlinx.serialization.json.JsonElement?): String? = when (content) {
    is JsonPrimitive -> content.contentOrNull

    is JsonArray -> {
        val parts = content.map(::textBlock)
        if (parts.any { it == null }) null else parts.joinToString("") { it.orEmpty() }
    }

    null, is JsonObject -> null
}

private fun textBlock(part: kotlinx.serialization.json.JsonElement): String? {
    val block = part as? JsonObject
    return if ((block?.get("type") as? JsonPrimitive)?.contentOrNull == "text") {
        (block.get("text") as? JsonPrimitive)?.contentOrNull
    } else {
        null
    }
}

private fun refuse(reason: IsolatedPromptFailure): Nothing = throw IsolatedKoogRefusal(reason)

private val FORBIDDEN_FIELDS = setOf(
    "system",
    "tool_choice",
    "functions",
    "function_call",
    "mcp_servers",
    "previous_response_id",
    "conversation",
)

private const val MAX_OUTPUT_TOKENS = 4096

private const val MAX_CONTENT_WRAPPERS = 8
