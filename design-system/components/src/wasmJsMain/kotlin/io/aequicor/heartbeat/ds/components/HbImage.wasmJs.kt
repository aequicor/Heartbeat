package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

internal actual fun decodeAttachmentImage(bytes: ByteArray): ImageBitmap {
    require(bytes.size <= MAX_IMAGE_BYTES) { "Preview image exceeds byte limit" }
    val image = Image.makeFromEncoded(bytes)
    require(image.width.toLong() * image.height <= MAX_IMAGE_PIXELS) { "Preview image exceeds pixel limit" }
    return image.toComposeImageBitmap()
}

private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
private const val MAX_IMAGE_PIXELS = 25L * 1024 * 1024
