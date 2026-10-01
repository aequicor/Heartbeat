package io.aequicor.heartbeat.ds.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal actual fun encodeAttachmentThumbnail(bytes: ByteArray): ByteArray {
    val metadata = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, metadata)
    val width = metadata.outWidth
    val height = metadata.outHeight
    require(width > 0 && height > 0 && width.toLong() * height <= ATTACHMENT_THUMBNAIL_MAX_PIXELS) {
        "Attachment thumbnail source exceeds pixel limit"
    }
    var sample = 1
    while (maxOf(width, height) / sample > ATTACHMENT_THUMBNAIL_EDGE * 2) sample *= 2
    val decoded = requireNotNull(
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }),
    )
    val scale = (ATTACHMENT_THUMBNAIL_EDGE.toFloat() / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
    val thumbnail = Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).roundToInt().coerceAtLeast(1),
        (decoded.height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
    return try {
        ByteArrayOutputStream().use { output ->
            check(
                thumbnail.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output),
            ) { "PNG thumbnail encoding failed" }
            output.toByteArray()
        }
    } finally {
        if (thumbnail !== decoded) thumbnail.recycle()
        decoded.recycle()
    }
}

private const val PNG_QUALITY = 100
