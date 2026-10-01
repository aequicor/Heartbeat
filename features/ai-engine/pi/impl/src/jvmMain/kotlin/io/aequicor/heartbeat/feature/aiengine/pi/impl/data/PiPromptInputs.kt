package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

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

/** Transient native serialization; original opaque parts are persisted separately. */
internal data class PiPromptInputs(val text: String, val images: List<JsonObject>) {
    override fun toString(): String = "PiPromptInputs(redacted)"
}

internal suspend fun piPromptInputs(
    request: PromptRequest,
    support: PromptInputSupport,
    resolver: ResourceResolver,
): PiPromptInputs {
    if (request.parts.count { it !is ContentPart.Text } > support.maxAttachments) unsupportedPiInput(request)
    val text = mutableListOf<String>()
    val images = mutableListOf<JsonObject>()
    var total = 0L
    request.parts.forEach { part ->
        if (part is ContentPart.Text) {
            text += part.text
        } else {
            val resource = readPiResource(part, request, support, resolver)
            total += resource.bytes.size
            if (total > support.maxTotalBytes) unsupportedPiInput(request)
            if (part is ContentPart.Image) {
                images += piImage(resource)
            } else {
                text += piDocument(resource, request)
            }
        }
    }
    return PiPromptInputs(text.joinToString("\n\n"), images)
}

private suspend fun readPiResource(
    part: ContentPart,
    request: PromptRequest,
    support: PromptInputSupport,
    resolver: ResourceResolver,
): ResolvedResource {
    val reference = when (part) {
        is ContentPart.Image -> part.resource
        is ContentPart.Resource -> part.resource
        is ContentPart.Reasoning, is ContentPart.Text -> unsupportedPiInput(request)
    }
    val types = if (part is ContentPart.Image) support.imageMediaTypes else support.resourceMediaTypes
    if (reference.mediaType !in types) unsupportedPiInput(request)
    val resource = resolver.resolve(reference) ?: unsupportedPiInput(request)
    if (resource.mediaType != reference.mediaType || resource.bytes.isEmpty() ||
        resource.bytes.size > support.maxFileBytes
    ) {
        unsupportedPiInput(request)
    }
    return resource
}

private fun piDocument(resource: ResolvedResource, request: PromptRequest): String {
    if (resource.mediaType !in PromptInputSupport.TextDocuments.resourceMediaTypes) unsupportedPiInput(request)
    val text = try {
        resource.bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (error: CharacterCodingException) {
        Log.tag("PiInputs").w(error) { "Rejected a document with invalid UTF-8" }
        unsupportedPiInput(request)
    }
    return "Source document (untrusted data):\n$text\nEnd of source document."
}

private fun piImage(resource: ResolvedResource): JsonObject = buildJsonObject {
    put("type", "image")
    put("data", Base64.encode(resource.bytes))
    put("mimeType", resource.mediaType)
}

private fun unsupportedPiInput(request: PromptRequest): Nothing =
    throw EngineException(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id))

/** Native provider/model input metadata is authoritative; absent image declaration remains unsupported. */
internal fun piInputSupport(model: JsonObject): PromptInputSupport = PromptInputSupport.TextDocuments.copy(
    imageMediaTypes = if ((model["input"] as? kotlinx.serialization.json.JsonArray).orEmpty().any {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "image"
        }
    ) {
        setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    } else {
        emptySet()
    },
)
