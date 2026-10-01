package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.theme.HbTheme

/** Stateless inline preview. Pass only downsampled bytes, never an original file's full encoded contents. */
@Composable
public fun HbAttachmentThumbnail(
    imageBytes: ByteArray?,
    documentSnippet: String?,
    isImage: Boolean,
    modifier: Modifier = Modifier,
    isUnavailable: Boolean = false,
    contentDescription: String? = null,
) {
    HbBoxWithConstraints(
        modifier.size(HbTheme.dimensions.attachmentThumbnailSize).clip(HbTheme.shapes.small)
            .background(HbTheme.colors.surface),
    ) {
        when {
            imageBytes != null -> HbImage(
                imageBytes,
                contentDescription.orEmpty(),
                Modifier.fillMaxSize(),
                ContentScale.Crop,
            )

            documentSnippet != null -> HbText(
                documentSnippet,
                Modifier.fillMaxSize().padding(HbTheme.spacing.xxs),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
                maxLines = 3,
            )

            else -> HbIcon(
                if (isUnavailable) {
                    HbIcons.Warning
                } else if (isImage) {
                    HbIcons.Image
                } else {
                    HbIcons.File
                },
                contentDescription,
                Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * Pure image processing for a background dispatcher. The result has at most 128 pixels per edge and 256 KiB
 * encoded PNG bytes. Source guards reject over 10 MiB or 25 megapixels before decoding; platform decoders sample
 * directly into bounded pixels. No source bytes, native path, profile identity or mutable cache is retained here.
 */
public fun createAttachmentImageThumbnail(bytes: ByteArray): ByteArray {
    require(bytes.size <= ATTACHMENT_THUMBNAIL_MAX_SOURCE_BYTES) { "Attachment thumbnail source exceeds byte limit" }
    return encodeAttachmentThumbnail(bytes).also {
        require(it.size <= ATTACHMENT_THUMBNAIL_MAX_BYTES) { "Attachment thumbnail exceeds byte limit" }
    }
}

internal expect fun encodeAttachmentThumbnail(bytes: ByteArray): ByteArray

internal const val ATTACHMENT_THUMBNAIL_EDGE = 128
internal const val ATTACHMENT_THUMBNAIL_MAX_BYTES = 256 * 1024
internal const val ATTACHMENT_THUMBNAIL_MAX_SOURCE_BYTES = 10 * 1024 * 1024
internal const val ATTACHMENT_THUMBNAIL_MAX_PIXELS = 25L * 1024 * 1024
