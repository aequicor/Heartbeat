package io.aequicor.heartbeat.feature.computeruse.impl.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun decodeComputerUseFrame(content: ByteArray): ImageBitmap {
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(content, 0, content.size)) {
        "Stored frame cannot be decoded"
    }
    // ImageBitmap retains this Bitmap; it must remain alive until Compose releases the preview.
    return bitmap.asImageBitmap()
}
