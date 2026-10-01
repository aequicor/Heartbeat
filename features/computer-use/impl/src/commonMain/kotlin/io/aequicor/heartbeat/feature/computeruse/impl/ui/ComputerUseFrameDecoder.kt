package io.aequicor.heartbeat.feature.computeruse.impl.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Platform image codec primitive for the common preview. */
internal expect fun decodeComputerUseFrame(content: ByteArray): ImageBitmap
