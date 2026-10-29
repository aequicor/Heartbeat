package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.core.logging.Log
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

internal suspend fun kimiPromptInputs(
    request: PromptRequest,
    support: PromptInputSupport,
    resolver: ResourceResolver,
): List<JsonObject> {
    if (request.parts.count { it !is ContentPart.Text } > support.maxAttachments) unsupportedKimiInput(request)
    val blocks = mutableListOf<JsonObject>()
    var total = 0L
    request.parts.forEach { part ->
        when (part) {
            is ContentPart.Text -> blocks += buildJsonObject {
                put("type", "text")
                put("text", part.text)
            }

            is ContentPart.Image -> {
                val resource = readKimiResource(part, request, support, resolver)
                total += resource.bytes.size
                if (total > support.maxTotalBytes) unsupportedKimiInput(request)
                blocks += buildJsonObject {
                    put("type", "image")
                    put("data", Base64.encode(resource.bytes))
                    put("mimeType", resource.mediaType)
                }
            }

            is ContentPart.Resource -> {
                val resource = readKimiResource(part, request, support, resolver)
                total += resource.bytes.size
                if (total > support.maxTotalBytes) unsupportedKimiInput(request)
                val text = try {
                    resource.bytes.decodeToString(throwOnInvalidSequence = true)
                } catch (error: CharacterCodingException) {
                    Log.tag("KimiInputs").w(error) { "Rejected a document with invalid UTF-8" }
                    unsupportedKimiInput(request)
                }
                blocks += buildJsonObject {
                    put("type", "resource")
                    put(
                        "resource",
                        buildJsonObject {
                            put("uri", "heartbeat-resource://${request.id.value}")
                            put("mimeType", resource.mediaType)
                            put("text", text)
                        },
                    )
                }
            }

            is ContentPart.Reasoning -> unsupportedKimiInput(request)
        }
    }
    return blocks
}

private suspend fun readKimiResource(
    part: ContentPart,
    request: PromptRequest,
    support: PromptInputSupport,
    resolver: ResourceResolver,
): ResolvedResource {
    val reference = when (part) {
        is ContentPart.Image -> part.resource
        is ContentPart.Resource -> part.resource
        is ContentPart.Reasoning, is ContentPart.Text -> unsupportedKimiInput(request)
    }
    val types = if (part is ContentPart.Image) support.imageMediaTypes else support.resourceMediaTypes
    if (reference.mediaType !in types) unsupportedKimiInput(request)
    val resource = resolver.resolve(reference) ?: unsupportedKimiInput(request)
    if (resource.mediaType != reference.mediaType || resource.bytes.isEmpty() ||
        resource.bytes.size > support.maxFileBytes
    ) {
        unsupportedKimiInput(request)
    }
    return resource
}

private fun unsupportedKimiInput(request: PromptRequest): Nothing =
    throw EngineException(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id))
