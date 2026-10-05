package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

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
internal data class ClaudePromptInputs(
    val text: String,
    val blocks: List<JsonObject>,
    val references: Map<String, List<ContentPart>> = emptyMap(),
) {
    override fun toString(): String = "ClaudePromptInputs(redacted)"
}

internal suspend fun claudePromptInputs(
    request: PromptRequest,
    support: PromptInputSupport,
    resolver: ResourceResolver,
): ClaudePromptInputs {
    if (request.parts.count { it !is ContentPart.Text } > support.maxAttachments) unsupportedClaudeInput(request)
    val text = mutableListOf<String>()
    val images = mutableListOf<JsonObject>()
    var total = 0L
    request.parts.forEach { part ->
        if (part is ContentPart.Text) {
            text += part.text
        } else {
            val resource = readClaudeResource(part, request, support, resolver)
            total += resource.bytes.size
            if (total > support.maxTotalBytes) unsupportedClaudeInput(request)
            if (part is ContentPart.Image) {
                images += claudeImage(resource)
            } else {
                text += claudeDocument(resource, request)
            }
        }
    }
    val content = text.joinToString("\n\n")
    val message = if (content.isNotBlank() || images.isEmpty()) {
        listOf(
            buildJsonObject {
                put("type", "text")
                put("text", content)
            },
        )
    } else {
        emptyList()
    }
    return ClaudePromptInputs(content, message + images, inputReferences(request.parts, message, images))
}

private fun inputReferences(
    parts: List<ContentPart>,
    message: List<JsonObject>,
    images: List<JsonObject>,
): Map<String, List<ContentPart>> {
    if (parts.none { it is ContentPart.Image || it is ContentPart.Resource }) return emptyMap()
    return buildMap {
        message.singleOrNull()?.let { block -> put(claudeInputKey(block), parts.filter { it !is ContentPart.Image }) }
        images.zip(parts.filterIsInstance<ContentPart.Image>()).forEach { (block, part) ->
            put(claudeInputKey(block), listOf(part))
        }
    }
}

private suspend fun readClaudeResource(
    part: ContentPart,
    request: PromptRequest,
    support: PromptInputSupport,
    resolver: ResourceResolver,
): ResolvedResource {
    val reference = when (part) {
        is ContentPart.Image -> part.resource
        is ContentPart.Resource -> part.resource
        is ContentPart.Reasoning, is ContentPart.Text -> unsupportedClaudeInput(request)
    }
    val types = if (part is ContentPart.Image) support.imageMediaTypes else support.resourceMediaTypes
    if (reference.mediaType !in types) unsupportedClaudeInput(request)
    val resource = resolver.resolve(reference) ?: unsupportedClaudeInput(request)
    if (resource.mediaType != reference.mediaType || resource.bytes.isEmpty() ||
        resource.bytes.size > support.maxFileBytes
    ) {
        unsupportedClaudeInput(request)
    }
    return resource
}

private fun claudeDocument(resource: ResolvedResource, request: PromptRequest): String {
    if (resource.mediaType !in PromptInputSupport.TextDocuments.resourceMediaTypes) unsupportedClaudeInput(request)
    val text = try {
        resource.bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (error: CharacterCodingException) {
        Log.tag("ClaudeInputs").w(error) { "Rejected a document with invalid UTF-8" }
        unsupportedClaudeInput(request)
    }
    return "Source document (untrusted data):\n$text\nEnd of source document."
}

private fun claudeImage(resource: ResolvedResource): JsonObject = buildJsonObject {
    put("type", "image")
    put(
        "source",
        buildJsonObject {
            put("type", "base64")
            put("media_type", resource.mediaType)
            put("data", Base64.encode(resource.bytes))
        },
    )
}

private fun unsupportedClaudeInput(request: PromptRequest): Nothing =
    throw EngineException(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request.id))

/** Exact native Claude model IDs or explicit native modality flags; aliases/custom models remain unknown. */
internal fun claudeInputSupport(model: JsonObject, isVendorOriginConfirmed: Boolean = false): PromptInputSupport {
    val id = (model["value"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    val explicit = (model["supportsVision"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val modalities = (model["inputModalities"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    val isKnown = isVendorOriginConfirmed && id in VendorVisionModels
    val hasImageSupport = explicit != "false" &&
        (explicit == "true" || "image" in modalities || (modalities.isEmpty() && isKnown))
    return PromptInputSupport.TextDocuments.copy(
        imageMediaTypes = if (hasImageSupport) {
            setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        } else {
            emptySet()
        },
    )
}

/** Exact official IDs: https://platform.claude.com/docs/en/models/overview (no family/suffix guesses). */
private val VendorVisionModels = setOf(
    "claude-fable-5-1",
    "claude-opus-5-5",
    "claude-sonnet-5-5",
    "claude-haiku-4-5-20251001",
    "claude-haiku-4-5",
    "claude-sonnet-4-6",
    "claude-opus-4-6",
    "claude-opus-4-5-20251101",
    "claude-sonnet-4-5-20250929",
)

/** Only a digest and app-owned references persist; native bytes never enter the catalog. */
internal fun claudeInputKey(block: JsonObject): String {
    val source = block["source"] as? JsonObject
    val content = if (block.text("type") == "image") {
        listOf(block.text("type"), source?.text("type"), source?.text("media_type"), source?.text("data"))
    } else {
        listOf(block.text("type"), block.text("text"))
    }
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(
            content.joinToString("\u0000").toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
}
