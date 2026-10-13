package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** The browser side panel attaches pages, not files: no file drop target. */
@Composable
internal actual fun platformAttachmentInput(
    enabled: Boolean,
    onFiles: (List<String>) -> Unit,
    onImage: (ByteArray) -> Unit,
): Modifier = Modifier

/** The async browser clipboard cannot be read synchronously; image paste is not offered in the browser. */
@Composable
internal actual fun rememberClipboardImageReader(): () -> ByteArray? = remember { { null } }
