package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/**
 * Captures user-initiated desktop file drops and image-paste shortcuts inside the modified subtree. Attach it to a
 * screen root: a key event reaches only the ancestors of the focused node, and focus often rests outside a composer.
 * Ordinary text paste keeps its native editor behavior. Disabled capture does not read the clipboard. Native
 * locations are transient caller inputs.
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
    isIconOnly: Boolean = false,
) {
    val paste = rememberHbPasteImageAction(onImage)
    if (isIconOnly) {
        HbIconButton(HbIcons.Image, label, paste, modifier, enabled = enabled)
        return
    }
    HbButton(
        label,
        onClick = paste,
        modifier = modifier,
        style = HbButtonStyle.Ghost,
        enabled = enabled,
    )
}

/**
 * A user-initiated image-paste action for an explicit button or menu item. The clipboard is read only
 * when the returned callback is invoked; the latest [onImage] receives the platform-encoded image.
 */
@Composable
public fun rememberHbPasteImageAction(onImage: (ByteArray) -> Unit): () -> Unit {
    val read = rememberClipboardImageReader()
    val latest = rememberUpdatedState(onImage)
    return remember(read) { { read()?.let(latest.value) } }
}

@Composable
internal expect fun platformAttachmentInput(
    enabled: Boolean,
    onFiles: (List<String>) -> Unit,
    onImage: (ByteArray) -> Unit,
): Modifier

@Composable
internal expect fun rememberClipboardImageReader(): () -> ByteArray?
