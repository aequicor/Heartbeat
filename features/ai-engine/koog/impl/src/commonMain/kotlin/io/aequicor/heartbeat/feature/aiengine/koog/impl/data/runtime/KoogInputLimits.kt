package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.serialization.json.JsonPrimitive

/**
 * Anthropic's 10 decimal MB encoded image limit and 32 decimal MB request limit need raw-byte headroom.
 * See https://platform.claude.com/docs/en/build-with-claude/vision#request-limits.
 */
internal fun providerInputSupport(provider: KoogProvider?, support: PromptInputSupport): PromptInputSupport =
    if (provider.isAnthropic()) {
        support.copy(
            maxFileBytes = minOf(support.maxFileBytes, ANTHROPIC_MAX_FILE_BYTES),
            maxTotalBytes = minOf(support.maxTotalBytes, ANTHROPIC_MAX_TOTAL_BYTES),
        )
    } else {
        support
    }

/** Actual serialized text/base64 of message history and the new input, plus SDK/tool framing headroom. */
internal class KoogEncodedRequestBudget(provider: KoogProvider, private val request: RequestId) {
    private val isRestricted = provider.isAnthropic()
    private var bytes = REQUEST_FRAMING_RESERVE

    fun append(parts: List<MessagePart.RequestPart>) {
        if (!isRestricted) return
        parts.forEach { part ->
            bytes += BLOCK_FRAMING_RESERVE + encodedBytes(part)
            if (bytes > ANTHROPIC_MAX_REQUEST_BYTES) {
                fail(EngineFailure.Request(RequestFailureReason.UnsupportedContent, request))
            }
        }
    }
}

private fun encodedBytes(part: MessagePart.RequestPart): Long = when (part) {
    is MessagePart.Text -> jsonBytes(part.text)

    is MessagePart.Attachment -> {
        val content = when (val source = part.source) {
            is AttachmentSource.Image -> source.content
            is AttachmentSource.File -> source.content
            is AttachmentSource.Audio, is AttachmentSource.Video -> error("Unexpected validated attachment format")
        }
        when (content) {
            is AttachmentContent.Binary.Base64 -> content.base64.length.toLong()
            is AttachmentContent.URL -> jsonBytes(content.url)
            is AttachmentContent.Binary.Bytes, is AttachmentContent.PlainText -> error("Unexpected validated encoding")
        }
    }

    is MessagePart.Tool.Result -> error("Unexpected tool result from the validated user content serializer")
}

private fun jsonBytes(text: String): Long = JsonPrimitive(text).toString().encodeToByteArray().size.toLong()

private fun KoogProvider?.isAnthropic(): Boolean =
    this == KoogProvider.Anthropic || this == KoogProvider.AnthropicCompatible

private const val ANTHROPIC_MAX_FILE_BYTES = 7_340_032L
private const val ANTHROPIC_MAX_TOTAL_BYTES = 20_971_520L
private const val ANTHROPIC_MAX_REQUEST_BYTES = 32_000_000L
private const val REQUEST_FRAMING_RESERVE = 2_097_152L
private const val BLOCK_FRAMING_RESERVE = 1024L
