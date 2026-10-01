package io.aequicor.heartbeat.ds.components

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun decodeAttachmentImage(bytes: ByteArray): ImageBitmap {
    require(bytes.size <= MAX_IMAGE_BYTES) { "Preview image exceeds byte limit" }
    val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
    require(
        dimensions.outWidth.toLong() * dimensions.outHeight <= MAX_IMAGE_PIXELS,
    ) { "Preview image exceeds pixel limit" }
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)).asImageBitmap()
}

private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
private const val MAX_IMAGE_PIXELS = 25L * 1024 * 1024
