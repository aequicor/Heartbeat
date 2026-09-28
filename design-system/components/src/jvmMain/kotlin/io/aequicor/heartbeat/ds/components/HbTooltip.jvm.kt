package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.ds.theme.HbTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun HbPlatformTooltip(
    text: String,
    modifier: Modifier,
    isEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (isEnabled && text.isNotBlank()) {
        TooltipArea(
            tooltip = { HbTooltipLabel(text) },
            modifier = modifier,
            delayMillis = HbTheme.motion.tooltipDelayMillis,
            content = content,
        )
    } else {
        Box(modifier, propagateMinConstraints = true) { content() }
    }
}
