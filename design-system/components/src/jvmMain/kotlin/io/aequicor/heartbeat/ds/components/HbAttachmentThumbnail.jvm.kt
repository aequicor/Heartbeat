package io.aequicor.heartbeat.ds.components

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.ceil

internal actual fun encodeAttachmentThumbnail(bytes: ByteArray): ByteArray =
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
        if (reader == null) return@use encodeSkiaThumbnail(bytes)
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(width > 0 && height > 0 && width.toLong() * height <= ATTACHMENT_THUMBNAIL_MAX_PIXELS) {
                "Attachment thumbnail source exceeds pixel limit"
            }
            val sample = ceil(maxOf(width, height).toDouble() / ATTACHMENT_THUMBNAIL_EDGE).toInt().coerceAtLeast(1)
            val options = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
            val image = reader.read(0, options)
            ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "png", output)) { "PNG thumbnail encoding is unavailable" }
                output.toByteArray()
            }
        } finally {
            reader.dispose()
        }
    }

/** Skia's WebP codec scales directly into the requested bitmap when the JDK has no reader for the format. */
private fun encodeSkiaThumbnail(bytes: ByteArray): ByteArray = Data.makeFromBytes(bytes).use { data ->
    Codec.makeFromData(data).use { codec ->
        val width = codec.imageInfo.width
        val height = codec.imageInfo.height
        require(width > 0 && height > 0 && width.toLong() * height <= ATTACHMENT_THUMBNAIL_MAX_PIXELS) {
            "Attachment thumbnail source exceeds pixel limit"
        }
        val scale = (ATTACHMENT_THUMBNAIL_EDGE.toDouble() / maxOf(width, height)).coerceAtMost(1.0)
        val thumbnailInfo = ImageInfo.makeN32Premul(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
        )
        encodeScaledBitmap(codec, thumbnailInfo)
    }
}

private fun encodeScaledBitmap(codec: Codec, info: ImageInfo): ByteArray = Bitmap().use { bitmap ->
    bitmap.allocPixels(info)
    codec.readPixels(bitmap)
    Image.makeFromBitmap(bitmap).use { image ->
        requireNotNull(image.encodeToData()).use { encoded -> encoded.bytes }
    }
}
