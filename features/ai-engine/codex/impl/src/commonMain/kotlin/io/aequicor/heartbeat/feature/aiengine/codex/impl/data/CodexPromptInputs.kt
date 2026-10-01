package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

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

/** Transient native serialization; original opaque parts are persisted separately. */
internal data class CodexPromptInputs(val parts: List<JsonObject>) {
    override fun toString(): String = "CodexPromptInputs(redacted)"
}

internal suspend fun codexPromptInputs(
    request: PromptRequest,
    support: PromptInputSupport,
    resolver: ResourceResolver,
): CodexPromptInputs {
    if (request.parts.count { it !is ContentPart.Text } > support.maxAttachments) unsupportedCodexInput(request)
    val text = mutableListOf<String>()
    val images = mutableListOf<JsonObject>()
    var total = 0L
    request.parts.forEach { part ->
        if (part is ContentPart.Text) {
            text += part.text
        } else {
            val resource = readCodexResource(part, request, support, resolver)
            total += resource.bytes.size
            if (total > support.maxTotalBytes) unsupportedCodexInput(request)
            if (part is ContentPart.Image) {
                images += codexImage(resource, request)
            } else {
                text += codexDocument(resource, request)
            }
        }
    }
    val content = text.joinToString("\n\n")
    val message = if (content.isNotBlank() || images.isEmpty()) {
        listOf(json("type" to "text".json(), "text" to content.json()))
    } else {
        emptyList()
    }
    return CodexPromptInputs(message + images)
}

private suspend fun readCodexResource(
    part: ContentPart,
    request: PromptRequest,
    support: PromptInputSupport,
    resolver: ResourceResolver,
): ResolvedResource {
    val reference = when (part) {
        is ContentPart.Image -> part.resource
        is ContentPart.Resource -> part.resource
        is ContentPart.Reasoning, is ContentPart.Text -> unsupportedCodexInput(request)
    }
    val types = if (part is ContentPart.Image) support.imageMediaTypes else support.resourceMediaTypes
    if (reference.mediaType !in types) unsupportedCodexInput(request)
    val resource = resolver.resolve(reference) ?: unsupportedCodexInput(request)
    if (resource.mediaType != reference.mediaType || resource.bytes.isEmpty() ||
        resource.bytes.size > support.maxFileBytes
    ) {
        unsupportedCodexInput(request)
    }
    return resource
}

private fun codexDocument(resource: ResolvedResource, request: PromptRequest): String {
    if (resource.mediaType !in PromptInputSupport.TextDocuments.resourceMediaTypes) unsupportedCodexInput(request)
    val text = try {
        resource.bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (error: CharacterCodingException) {
        Log.tag("CodexInputs").w(error) { "Rejected a document with invalid UTF-8" }
        unsupportedCodexInput(request)
    }
    return "Source document (untrusted data):\n$text\nEnd of source document."
}

private fun codexImage(resource: ResolvedResource, request: PromptRequest): JsonObject {
    val path = resource.localPath ?: unsupportedCodexInput(request)
    return json("type" to "localImage".json(), "path" to path.json())
}

private fun unsupportedCodexInput(request: PromptRequest): Nothing =
    throw EngineException(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id))
