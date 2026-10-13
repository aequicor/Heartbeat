package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Compose for web has no `TooltipArea`: the browser side panel shows no hover hints, controls keep their visible
 * labels and accessible names.
 */
@Composable
internal actual fun HbPlatformTooltip(
    text: String,
    modifier: Modifier,
    isEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    Box(modifier, propagateMinConstraints = true) { content() }
}
