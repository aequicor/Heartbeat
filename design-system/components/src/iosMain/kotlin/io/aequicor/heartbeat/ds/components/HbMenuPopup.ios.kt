package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

@Composable
internal actual fun HbMenuPopup(
    position: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    focusable: Boolean,
    content: @Composable () -> Unit,
) {
    Popup(position, onDismissRequest, PopupProperties(focusable = focusable), content)
}
