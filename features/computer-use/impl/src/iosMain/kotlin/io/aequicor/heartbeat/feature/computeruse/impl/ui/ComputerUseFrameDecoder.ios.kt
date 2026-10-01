package io.aequicor.heartbeat.feature.computeruse.impl.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

internal actual fun decodeComputerUseFrame(content: ByteArray): ImageBitmap =
    Image.makeFromEncoded(content).toComposeImageBitmap()
