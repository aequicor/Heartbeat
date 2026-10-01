package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import kotlin.io.encoding.Base64

/** Only model metadata, never the synthetic textModel used for serializer configuration, proves support. */
internal fun koogInputSupport(model: LLModel): PromptInputSupport = PromptInputSupport(
    imageMediaTypes = if (LLMCapability.Vision.Image in model.capabilities.orEmpty()) {
        setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    } else {
        emptySet()
    },
    resourceMediaTypes = PromptInputSupport.TextDocuments.resourceMediaTypes +
        if (LLMCapability.Document in model.capabilities.orEmpty()) setOf("application/pdf") else emptySet(),
)

/** Resolves host IDs for this transport request only; durable histories retain the original parts. */
internal suspend fun resolveKoogInputs(
    parts: List<ContentPart>,
    support: PromptInputSupport,
    resolver: ResourceResolver,
    request: RequestId? = null,
): List<ContentPart> {
    if (parts.count(::countsAsAttachment) > support.maxAttachments) unsupportedInput(request)
    val budget = InputBudget(support)
    return parts.map { part ->
        val reference = reference(part) ?: return@map part
        val formats = if (part is ContentPart.Image) support.imageMediaTypes else support.resourceMediaTypes
        if (reference.mediaType !in formats) unsupportedInput(request)
        val transported = transportReference(reference, budget.limits(part), resolver, request)
        budget.record(part, transported.size, request)
        if (part is ContentPart.Image) {
            ContentPart.Image(
                transported.reference,
            )
        } else {
            ContentPart.Resource(transported.reference)
        }
    }
}

/** User-file quotas and generated research context each have a separate byte bound. */
private class InputBudget(private val support: PromptInputSupport) {
    private var attachments = 0L
    private var context = 0L

    fun limits(part: ContentPart): PromptInputSupport = if (countsAsAttachment(part)) {
        support
    } else {
        support.copy(maxFileBytes = MAX_INLINE_CONTEXT_BYTES)
    }

    fun record(part: ContentPart, size: Int, request: RequestId?) {
        if (countsAsAttachment(part)) attachments += size else context += size
        if (attachments > support.maxTotalBytes || context > MAX_INLINE_CONTEXT_BYTES) unsupportedInput(request)
    }
}

private data class TransportedReference(val reference: ResourceRef, val size: Int) {
    override fun toString(): String = "TransportedReference(size=$size)"
}

private suspend fun transportReference(
    reference: ResourceRef,
    support: PromptInputSupport,
    resolver: ResourceResolver,
    request: RequestId?,
): TransportedReference {
    val resolved = resolver.resolve(reference)
    val bytes = resolved?.bytes ?: inlineBytes(reference, support, request)
    if (resolved != null && resolved.mediaType != reference.mediaType) unsupportedInput(request)
    if (bytes != null && (bytes.isEmpty() || bytes.size > support.maxFileBytes)) unsupportedInput(request)
    val transported = if (resolved != null) {
        ResourceRef("data:${resolved.mediaType};base64,${Base64.encode(resolved.bytes)}", resolved.mediaType)
    } else {
        reference
    }
    return TransportedReference(transported, bytes?.size ?: 0)
}

private fun reference(part: ContentPart): ResourceRef? = when (part) {
    is ContentPart.Image -> part.resource
    is ContentPart.Resource -> part.resource
    is ContentPart.Text, is ContentPart.Reasoning -> null
}

/** Generated research headers/previous context are bounded inline text, not user attachment slots. */
private fun countsAsAttachment(part: ContentPart): Boolean = when (part) {
    is ContentPart.Image -> true

    is ContentPart.Resource -> !(
        part.resource.mediaType in PromptInputSupport.TextDocuments.resourceMediaTypes &&
            part.resource.id.startsWith("data:${part.resource.mediaType};base64,")
    )

    is ContentPart.Text, is ContentPart.Reasoning -> false
}

private fun inlineBytes(reference: ResourceRef, support: PromptInputSupport, request: RequestId?): ByteArray? {
    if (!reference.id.startsWith("data:")) return null
    val prefix = "data:${reference.mediaType};base64,"
    if (!reference.id.startsWith(prefix)) unsupportedInput(request)
    val encoded = reference.id.removePrefix(prefix)
    if (encoded.length.toLong() >
        ((support.maxFileBytes + BASE64_INPUT_BLOCK - 1) / BASE64_INPUT_BLOCK) * BASE64_OUTPUT_BLOCK
    ) {
        unsupportedInput(
            request,
        )
    }
    return try {
        Base64.decode(encoded)
    } catch (error: IllegalArgumentException) {
        log.w(error.sanitized()) {
            "Rejected malformed attachment encoding"
        }
        unsupportedInput(request)
    }
}

private fun unsupportedInput(request: RequestId?): Nothing =
    fail(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request))

private const val BASE64_INPUT_BLOCK = 3
private const val BASE64_OUTPUT_BLOCK = 4
private val log = Log.tag("KoogInputs")

private const val MAX_INLINE_CONTEXT_BYTES = 10_485_760L
