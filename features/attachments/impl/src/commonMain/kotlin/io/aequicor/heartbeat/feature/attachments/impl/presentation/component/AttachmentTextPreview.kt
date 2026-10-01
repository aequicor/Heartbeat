package io.aequicor.heartbeat.feature.attachments.impl.presentation.component

/** Bounded reading surface; export always uses the complete original bytes in storage. */
internal data class AttachmentTextPreview(val text: String, val isTruncated: Boolean)

/** UTF-8 needs at most four bytes per character; decoding stays bounded before Compose measures the text. */
internal fun ByteArray.toTextPreview(): AttachmentTextPreview {
    val decoded = decodeToString(endIndex = minOf(size, TEXT_PREVIEW_CHARACTERS * UTF8_MAX_BYTES))
    return AttachmentTextPreview(
        decoded.take(TEXT_PREVIEW_CHARACTERS),
        size > TEXT_PREVIEW_CHARACTERS * UTF8_MAX_BYTES || decoded.length > TEXT_PREVIEW_CHARACTERS,
    )
}

private const val TEXT_PREVIEW_CHARACTERS = 64 * 1024
private const val UTF8_MAX_BYTES = 4
