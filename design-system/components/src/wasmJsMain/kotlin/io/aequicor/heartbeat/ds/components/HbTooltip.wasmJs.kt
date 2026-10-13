package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Browser hints share the tested Foundation implementation; accessible names remain on the controls. */
@Composable
internal actual fun HbPlatformTooltip(
    text: String,
    modifier: Modifier,
    isEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    HbHoverTooltip(text, modifier, isEnabled, content)
}
