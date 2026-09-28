package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun HbPlatformWindowDragArea(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier, propagateMinConstraints = true) { content() }
}
