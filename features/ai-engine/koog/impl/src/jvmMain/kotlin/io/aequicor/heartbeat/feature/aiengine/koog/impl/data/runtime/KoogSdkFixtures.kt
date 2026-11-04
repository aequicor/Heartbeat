package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.core.network.OfflineHttpExchange
import io.aequicor.heartbeat.core.network.OfflineHttpResponse
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider

/** Independent wire goldens for pinned Koog 1.3; no serializer under assessment constructs the expected bytes. */
internal fun sdkExchange(provider: KoogProvider, check: KoogSdkCheck): OfflineHttpExchange {
    val isStreaming = check != KoogSdkCheck.CompletionCodec
    val path = when (provider) {
        KoogProvider.OpenAI, KoogProvider.OpenAICompatible -> "/v1/chat/completions"
        KoogProvider.AlibabaQwen -> "/compatible-mode/v1/chat/completions"
        KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> "/v1/messages"
        KoogProvider.Ollama -> "/api/chat"
    }
    val headers = buildMap {
        put("Content-Type", "application/json")
        when (provider) {
            KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> {
                put("x-api-key", SDK_PROBE_KEY)
                put("anthropic-version", "2023-06-01")
            }

            KoogProvider.Ollama -> Unit

            KoogProvider.OpenAI, KoogProvider.OpenAICompatible, KoogProvider.AlibabaQwen ->
                put("Authorization", "Bearer $SDK_PROBE_KEY")
        }
    }
    return OfflineHttpExchange(
        "POST",
        provider.sdkProbeOrigin.value + path,
        headers,
        sdkRequest(provider, isStreaming),
        if (check == KoogSdkCheck.Cancellation) {
            OfflineHttpResponse.AwaitCancellation
        } else {
            OfflineHttpResponse.Reply(
                headers = mapOf(
                    "Content-Type" to when {
                        !isStreaming -> "application/json"
                        provider == KoogProvider.Ollama -> "application/x-ndjson"
                        else -> "text/event-stream"
                    },
                ),
                body = if (isStreaming) sdkStream(provider, check) else sdkCompletion(provider),
            )
        },
    )
}

/** Ordinary native turns use default output limits and a separate synthetic profile credential. */
internal fun nativeWireExchange(
    provider: KoogProvider,
    check: KoogSdkCheck = KoogSdkCheck.StreamStop,
): OfflineHttpExchange {
    val exchange = sdkExchange(provider, check)
    val header = when (provider) {
        KoogProvider.Ollama -> emptyMap()

        KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> mapOf("x-api-key" to "synthetic-key")

        KoogProvider.OpenAI, KoogProvider.OpenAICompatible, KoogProvider.AlibabaQwen ->
            mapOf("Authorization" to "Bearer synthetic-key")
    }
    return exchange.copy(
        headers = exchange.headers + header,
        body = exchange.body.replace(",\"max_completion_tokens\":8", "")
            .replace("\"max_tokens\":8", "\"max_tokens\":2048"),
    )
}

internal val KoogProvider.sdkProbeOrigin: EndpointOrigin
    get() = if (isOriginEditable) EndpointOrigin("https://offline.example") else origin

private fun sdkRequest(provider: KoogProvider, isStreaming: Boolean): String {
    val options = if (isStreaming) ""","stream_options":{"include_usage":true},"tools":[]""" else ""
    return when (provider) {
        KoogProvider.OpenAI, KoogProvider.OpenAICompatible, KoogProvider.AlibabaQwen -> wire(
            """
            {"messages":[{"role":"user","content":"READY"}],
            "model":"heartbeat-contract","max_completion_tokens":8,"stream":$isStreaming$options}
            """,
        )

        KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> wire(
            """
            {"model":"heartbeat-contract",
            "messages":[{"role":"user","content":[{"type":"text","text":"READY"}]}],
            "max_tokens":8,"stream":$isStreaming,"system":[],"tools":[]}
            """,
        )

        KoogProvider.Ollama -> wire(
            """
            {"model":"heartbeat-contract","messages":[{"role":"user","content":"READY"}],
            "options":{},"stream":$isStreaming}
            """,
        )
    }
}

private fun sdkCompletion(provider: KoogProvider): String = when (provider) {
    KoogProvider.OpenAI, KoogProvider.OpenAICompatible, KoogProvider.AlibabaQwen ->
        wire(
            """
            {"id":"m",
            "object":"chat.completion",
            "created":0,
            "model":"heartbeat-contract",
            "choices":[{"index":0,
            "message":{"role":"assistant",
            "content":"READY"},
            "finish_reason":"stop"}],
            "usage":{"prompt_tokens":1,
            "completion_tokens":1,
            "total_tokens":2}}
            """,
        )

    KoogProvider.Anthropic, KoogProvider.AnthropicCompatible ->
        wire(
            """
            {"id":"m",
            "type":"message",
            "role":"assistant",
            "model":"heartbeat-contract",
            "content":[{"type":"text",
            "text":"READY"}],
            "stop_reason":"end_turn",
            "stop_sequence":null,
            "usage":{"input_tokens":1,
            "output_tokens":1}}
            """,
        )

    KoogProvider.Ollama -> ollamaChunk("READY", isDone = true)
}

private fun sdkStream(provider: KoogProvider, check: KoogSdkCheck): String {
    val events = sdkStreamEvents(provider)
    val selected = when (check) {
        KoogSdkCheck.UnterminatedStream -> when (provider) {
            KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> events.take(3)

            KoogProvider.OpenAI, KoogProvider.OpenAICompatible, KoogProvider.AlibabaQwen, KoogProvider.Ollama ->
                events.take(1)
        }

        KoogSdkCheck.MalformedStream -> events.take(1) + "{broken-json" + events.drop(1)

        KoogSdkCheck.CompletionCodec, KoogSdkCheck.StreamStop, KoogSdkCheck.Cancellation -> events
    }
    return if (provider == KoogProvider.Ollama) {
        selected.joinToString("\n", postfix = "\n")
    } else {
        selected.joinToString("") { "data: $it\n\n" }
    }
}

private fun sdkStreamEvents(provider: KoogProvider): List<String> = when (provider) {
    KoogProvider.OpenAI, KoogProvider.OpenAICompatible, KoogProvider.AlibabaQwen -> listOf(
        wire(
            """
            {"id":"m",
            "object":"chat.completion.chunk",
            "created":0,
            "model":"heartbeat-contract",
            "choices":[{"index":0,
            "delta":{"role":"assistant",
            "content":"READY"},
            "finish_reason":null}]}
            """,
        ),
        wire(
            """
            {"id":"m",
            "object":"chat.completion.chunk",
            "created":0,
            "model":"heartbeat-contract",
            "choices":[{"index":0,
            "delta":{},
            "finish_reason":"stop"}]}
            """,
        ),
        "[DONE]",
    )

    KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> listOf(
        wire(
            """
            {"type":"message_start",
            "message":{"id":"m",
            "type":"message",
            "role":"assistant",
            "model":"heartbeat-contract",
            "content":[],
            "stop_reason":null,
            "stop_sequence":null,
            "usage":{"input_tokens":1,
            "output_tokens":0}}}
            """,
        ),
        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"READY"}}""",
        """{"type":"content_block_stop","index":0}""",
        wire(
            """
            {"type":"message_delta",
            "delta":{"stop_reason":"end_turn",
            "stop_sequence":null},
            "usage":{"output_tokens":1}}
            """,
        ),
        """{"type":"message_stop"}""",
    )

    KoogProvider.Ollama -> listOf(ollamaChunk("READY", false), ollamaChunk("", true))
}

private fun ollamaChunk(text: String, isDone: Boolean): String = wire(
    """
    {"model":"heartbeat-contract",
    "created_at":"2026-01-01T00:00:00Z",
    "message":{"role":"assistant",
    "content":"$text"},
    "done":$isDone,
    "done_reason":"${if (isDone) "stop" else ""}",
    "prompt_eval_count":1,
    "eval_count":1}
    """,
)

internal const val SDK_PROBE_MODEL = "heartbeat-contract"
internal const val SDK_PROBE_KEY = "offline-contract-key"
internal const val SDK_PROBE_TEXT = "READY"
internal const val SDK_PROBE_TOKENS = 8

/** Only removes source indentation/newlines from fixed fixture JSON. No codec under assessment is used. */
private fun wire(value: String): String = value.lineSequence().joinToString("") { it.trim() }
