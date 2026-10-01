package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPasteboard
import platform.posix.memcpy

@Composable
internal actual fun platformAttachmentInput(
    enabled: Boolean,
    onFiles: (List<String>) -> Unit,
    onImage: (ByteArray) -> Unit,
): Modifier = Modifier

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberClipboardImageReader(): () -> ByteArray? = remember {
    {
        try {
            val image = UIPasteboard.generalPasteboard.image
            val data = image?.let { UIImagePNGRepresentation(it) }
            if (data == null || data.length > MAX_CLIPBOARD_BYTES.toULong() || data.length == 0uL) {
                null
            } else {
                ByteArray(data.length.toInt()).also { bytes ->
                    bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.tag("DS/AttachmentInput").w(error) { "Clipboard image read failed" }
            null
        }
    }
}

private const val MAX_CLIPBOARD_BYTES = 10 * 1024 * 1024
