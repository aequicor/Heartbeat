package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal actual fun HbPlatformDialog(
    onDismissRequest: () -> Unit,
    hasOverlay: Boolean,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false), content)
}
