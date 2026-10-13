package io.aequicor.heartbeat.ds.components

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.Managed

/**
 * Skia's codec scales directly into the thumbnail bitmap, as on desktop for formats the JDK cannot read.
 * Skia objects on wasm are not `AutoCloseable`, so they are closed explicitly.
 */
internal actual fun encodeAttachmentThumbnail(bytes: ByteArray): ByteArray = Data.makeFromBytes(bytes).closing { data ->
    Codec.makeFromData(data).closing { codec ->
        val width = codec.imageInfo.width
        val height = codec.imageInfo.height
        require(width > 0 && height > 0 && width.toLong() * height <= ATTACHMENT_THUMBNAIL_MAX_PIXELS) {
            "Attachment thumbnail source exceeds pixel limit"
        }
        val scale = (ATTACHMENT_THUMBNAIL_EDGE.toDouble() / maxOf(width, height)).coerceAtMost(1.0)
        val info = ImageInfo.makeN32Premul(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
        )
        Bitmap().closing { bitmap ->
            bitmap.allocPixels(info)
            codec.readPixels(bitmap)
            Image.makeFromBitmap(bitmap).closing { image ->
                requireNotNull(image.encodeToData()).closing { encoded -> encoded.bytes }
            }
        }
    }
}

private inline fun <T : Managed, R> T.closing(block: (T) -> R): R = try {
    block(this)
} finally {
    close()
}
