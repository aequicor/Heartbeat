package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Anchored to the control bounds instead of the cursor: a cursor-placed hint near the window bottom flips onto
 * the pointer and swallows the next click, so the hint must never overlap its own control.
 */
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
            tooltipPlacement = TooltipPlacement.ComponentRect(
                anchor = Alignment.BottomCenter,
                alignment = Alignment.BottomCenter,
                offset = DpOffset(x = HbTheme.spacing.none, y = HbTheme.spacing.xs),
            ),
            content = content,
        )
    } else {
        Box(modifier, propagateMinConstraints = true) { content() }
    }
}
