package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.PopupPositionProvider

/**
 * Focusable host of an anchored menu sheet placed by [position]. The platform owns outside-click dismissal:
 * desktop must also observe clicks over embedded native surfaces, which never reach Compose.
 */
@Composable
internal expect fun HbMenuPopup(
    position: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
)
