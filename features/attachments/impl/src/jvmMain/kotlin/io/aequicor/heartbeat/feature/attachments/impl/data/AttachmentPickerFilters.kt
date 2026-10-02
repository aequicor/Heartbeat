package io.aequicor.heartbeat.feature.attachments.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport

/**
 * Extensions the native dialog offers for the formats the model accepts: the mask contains exactly the
 * types confirmed by [PromptInputSupport] and implemented by this importer. An empty list means the model
 * accepts nothing importable — the caller reports `AttachmentFailure.Unsupported`.
 */
internal fun attachmentExtensions(support: PromptInputSupport): List<String> = support.allowedMediaTypes
    .sorted()
    .flatMap { mime ->
        when (mime) {
            "text/plain" -> listOf("txt")
            "text/markdown" -> listOf("md", "markdown")
            "application/pdf" -> listOf("pdf")
            "image/png" -> listOf("png")
            "image/jpeg" -> listOf("jpg", "jpeg")
            "image/gif" -> listOf("gif")
            "image/webp" -> listOf("webp")
            else -> emptyList()
        }
    }

/** The save dialog offers the attachment's own extension as its type mask and default suffix. */
internal fun saveExtensions(name: String): List<String> =
    name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty()
