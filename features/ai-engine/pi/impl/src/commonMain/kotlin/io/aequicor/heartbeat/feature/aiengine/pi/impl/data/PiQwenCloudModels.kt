package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Capabilities confirmed for QwenCloud's exact Token Plan Chat Completions route and model, checked 2026-10-20:
 * https://docs.qwencloud.com/api-reference/chat/openai-chat (thinking switch and low/medium/xhigh effort),
 * https://docs.qwencloud.com/developer-guides/clients-and-developer-tools/opencode (text/image input).
 * Pi 0.87.1 has no catalog for this host, and its /models list contains IDs only. This is not an alias of the
 * older ModelStudio provider: credentials and requests keep their original endpoint. Other models, protocols,
 * paths and hosts receive no inferred capabilities. Server declarations and an exact native catalog take priority.
 * Live Chat Completions rejects the developer role (HTTP 400), so Pi must use system messages.
 * No context/output capacity is inferred from the older provider or client configuration examples.
 */
internal fun piQwenCloudModel(api: String, baseUrl: String, model: String): JsonObject? {
    if (api != "openai-completions" ||
        baseUrl.trimEnd('/') != "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1" ||
        model != "qwen3.8-max"
    ) {
        return null
    }
    return buildJsonObject {
        put("reasoning", true)
        put(
            "input",
            buildJsonArray {
                add("text")
                add("image")
            },
        )
        put(
            "thinkingLevelMap",
            buildJsonObject {
                listOf("off", "minimal", "high", "max").forEach { put(it, JsonNull) }
                listOf("low", "medium", "xhigh").forEach { put(it, it) }
            },
        )
        put(
            "compat",
            buildJsonObject {
                put("thinkingFormat", "qwen")
                put("supportsReasoningEffort", true)
                put("supportsDeveloperRole", false)
            },
        )
    }
}
