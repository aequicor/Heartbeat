package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException

/** Bounded original image preview shared by platform kits through Foundation rendering. */
@Composable
public fun HbImage(
    bytes: ByteArray,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val image = remember(bytes) {
        try {
            decodeAttachmentImage(bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.tag("DS/Image").w(error) { "Image decoding failed" }
            null
        }
    }
    if (image == null) {
        HbIcon(HbIcons.Image, contentDescription, modifier)
    } else {
        Image(image, contentDescription, modifier, contentScale = contentScale)
    }
}

internal expect fun decodeAttachmentImage(bytes: ByteArray): ImageBitmap
