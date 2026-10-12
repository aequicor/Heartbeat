package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.PopupPositionProvider

/**
 * Host of an anchored popup sheet placed by [position]: menus and the composer's popups. The platform owns
 * outside-click dismissal of a [focusable] popup: desktop must also observe clicks over embedded native surfaces,
 * which never reach Compose, and an outside click never activates the content beneath. A non-[focusable] popup
 * (suggestions under a focused editor) leaves keyboard focus where it is and lets outside input through to the
 * content beneath; its owner closes it when that content takes focus away.
 */
@Composable
internal expect fun HbMenuPopup(
    position: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    focusable: Boolean = true,
    content: @Composable () -> Unit,
)
