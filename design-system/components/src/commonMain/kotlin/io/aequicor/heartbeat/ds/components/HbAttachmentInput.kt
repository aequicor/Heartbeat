package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Captures user-initiated desktop file drops and image-paste shortcuts. Ordinary text paste keeps its native
 * editor behavior. Disabled capture does not read the clipboard. Native locations are transient caller inputs.
 */
@Composable
public fun hbAttachmentInput(
    enabled: Boolean,
    onFiles: (List<String>) -> Unit,
    onImage: (ByteArray) -> Unit,
): Modifier = platformAttachmentInput(enabled, onFiles, onImage)

/** Explicit clipboard image action for touch platforms and accessible desktop menus. */
@Composable
public fun HbPasteImageButton(
    label: String,
    onImage: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val read = rememberClipboardImageReader()
    HbButton(
        label,
        onClick = { read()?.let(onImage) },
        modifier = modifier,
        style = HbButtonStyle.Ghost,
        enabled = enabled,
    )
}

@Composable
internal expect fun platformAttachmentInput(
    enabled: Boolean,
    onFiles: (List<String>) -> Unit,
    onImage: (ByteArray) -> Unit,
): Modifier

@Composable
internal expect fun rememberClipboardImageReader(): () -> ByteArray?
