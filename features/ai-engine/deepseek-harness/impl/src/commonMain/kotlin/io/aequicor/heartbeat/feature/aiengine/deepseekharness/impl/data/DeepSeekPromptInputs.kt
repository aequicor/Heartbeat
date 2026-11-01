package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * Maps prompt parts into standard ACP content blocks. Inline images use the canonical base64 form DSH validates:
 * standard alphabet, padding and no whitespace. Media types, counts and byte budgets come from the support the
 * connection advertised during `initialize`; everything else is rejected before the turn is accepted.
 */
internal suspend fun dshPromptInputs(
    request: PromptRequest,
    support: PromptInputSupport,
    resolver: ResourceResolver,
): List<JsonObject> {
    if (request.parts.count { it !is ContentPart.Text } > support.maxAttachments) unsupportedInput(request)
    val blocks = mutableListOf<JsonObject>()
    var total = 0L
    request.parts.forEach { part ->
        when (part) {
            is ContentPart.Text -> blocks += buildJsonObject {
                put("type", "text")
                put("text", part.text)
            }

            is ContentPart.Image -> {
                val resource = readDshImage(part, request, support, resolver)
                total += resource.bytes.size
                if (total > support.maxTotalBytes) unsupportedInput(request)
                blocks += buildJsonObject {
                    put("type", "image")
                    put("data", Base64.encode(resource.bytes))
                    put("mimeType", resource.mediaType)
                }
            }

            is ContentPart.Reasoning, is ContentPart.Resource -> unsupportedInput(request)
        }
    }
    return blocks
}

private suspend fun readDshImage(
    part: ContentPart.Image,
    request: PromptRequest,
    support: PromptInputSupport,
    resolver: ResourceResolver,
): ResolvedResource {
    val reference = part.resource
    if (reference.mediaType !in support.imageMediaTypes) unsupportedInput(request)
    val resource = resolver.resolve(reference) ?: unsupportedInput(request)
    if (resource.mediaType != reference.mediaType || resource.bytes.isEmpty() ||
        resource.bytes.size > support.maxFileBytes
    ) {
        unsupportedInput(request)
    }
    return resource
}

private fun unsupportedInput(request: PromptRequest): Nothing =
    throw EngineException(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id))
