package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Maps prompt parts into standard ACP content blocks. Only text is admitted for now; images and embedded resources
 * are rejected until the model's input support is confirmed through discovery.
 */
internal fun dshPromptInputs(request: PromptRequest): List<JsonObject> {
    val blocks = mutableListOf<JsonObject>()
    request.parts.forEach { part ->
        when (part) {
            is ContentPart.Text -> blocks += buildJsonObject {
                put("type", "text")
                put("text", part.text)
            }

            else -> unsupportedInput(request)
        }
    }
    return blocks
}

private fun unsupportedInput(request: PromptRequest): Nothing =
    throw EngineException(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id))
