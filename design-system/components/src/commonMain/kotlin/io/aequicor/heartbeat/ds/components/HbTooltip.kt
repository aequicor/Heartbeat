package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Delayed desktop hover hint. Mobile retains the content and its touch behavior without a popup.
 * Keep the full accessible label on the control; the tooltip is supplementary visual information.
 */
@Composable
@NonRestartableComposable
fun HbTooltip(
    text: String,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    HbPlatformTooltip(text, modifier, isEnabled, content)
}

@Composable
internal expect fun HbPlatformTooltip(
    text: String,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    content: @Composable () -> Unit,
)

@Composable
internal fun HbTooltipLabel(text: String, modifier: Modifier = Modifier) {
    HbText(
        text,
        modifier.widthIn(max = HbTheme.dimensions.tooltipMaxWidth)
            .background(HbTheme.colors.textPrimary, RoundedCornerShape(HbTheme.dimensions.controlCornerRadius))
            .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.xs),
        style = HbTheme.typography.caption,
        color = HbTheme.colors.surface,
    )
}

@Preview
@Composable
private fun TooltipLightPreview() {
    HbTheme(darkTheme = false) { HbTooltipLabel("Search conversations · ⌘K") }
}

@Preview
@Composable
private fun TooltipDarkPreview() {
    HbTheme(darkTheme = true) { HbTooltipLabel("Search conversations · ⌘K") }
}
