package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme

/** Semantic color intent shared by indicators and chat presentations. */
public enum class HbTone { Neutral, Brand, Success, Warning, Danger }

/** Raised soft container. Slot content retains its own semantics and interaction policy. */
@Composable
public fun HbCard(
    modifier: Modifier = Modifier,
    background: Color = HbTheme.colors.surface,
    contentPadding: Dp = HbTheme.spacing.xl,
    content: @Composable ColumnScope.() -> Unit,
) {
    HbColumn(
        modifier = modifier
            .hbSurface(background, HbTheme.shapes.large)
            .padding(contentPadding),
        content = content,
    )
}

/** A compact text status; meaning is communicated by its label as well as its color. */
@Composable
public fun HbBadge(text: String, modifier: Modifier = Modifier, tone: HbTone = HbTone.Neutral) {
    val palette = tonePalette(tone)
    HbText(
        text = text,
        modifier = modifier
            .background(palette.background, HbTheme.shapes.small)
            .padding(horizontal = HbTheme.spacing.s, vertical = HbTheme.spacing.xs),
        style = HbTheme.typography.caption,
        color = palette.foreground,
    )
}

@Immutable
internal data class TonePalette(val background: Color, val foreground: Color, val accent: Color)

@Composable
internal fun tonePalette(tone: HbTone): TonePalette {
    val colors = HbTheme.colors
    return when (tone) {
        HbTone.Neutral -> TonePalette(colors.surfaceElevated, colors.textPrimary, colors.outlineSubtle)
        HbTone.Brand -> TonePalette(colors.accentMuted, colors.onAccentMuted, colors.brand)
        HbTone.Success -> TonePalette(colors.successContainer, colors.textPrimary, colors.success)
        HbTone.Warning -> TonePalette(colors.warningContainer, colors.textPrimary, colors.warning)
        HbTone.Danger -> TonePalette(colors.errorContainer, colors.textPrimary, colors.error)
    }
}
