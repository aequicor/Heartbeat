package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Theme typography with explicit text, style and color overrides.
 *
 * Single-line text that does not fit is clipped and fades out toward its trailing edge instead of
 * ending with an ellipsis, like native desktop sidebars; multi-line text keeps the ellipsis.
 */
@Composable
public fun HbText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = HbTheme.typography.body,
    color: Color = HbTheme.colors.textPrimary,
    maxLines: Int = Int.MAX_VALUE,
    isOverflowTooltipEnabled: Boolean = false,
) {
    var hasOverflow by remember(text) { mutableStateOf(false) }
    val isSingleLine = maxLines == 1
    val body: @Composable (Modifier) -> Unit = { textModifier ->
        BasicText(
            text = text,
            modifier = if (isSingleLine) textModifier.hbTrailingFade(hasOverflow) else textModifier,
            style = style.copy(color = color),
            maxLines = maxLines,
            softWrap = !isSingleLine,
            overflow = if (isSingleLine) TextOverflow.Clip else TextOverflow.Ellipsis,
            onTextLayout = { hasOverflow = it.hasVisualOverflow },
        )
    }
    if (isOverflowTooltipEnabled) {
        HbTooltip(text, modifier, isEnabled = hasOverflow) { body(Modifier) }
    } else {
        body(modifier)
    }
}

/** Masks the trailing [HbDimensions.textOverflowFade] of clipped content to transparent. */
@Composable
private fun Modifier.hbTrailingFade(isActive: Boolean): Modifier {
    if (!isActive) return this
    val fade = HbTheme.dimensions.textOverflowFade
    // DstIn keeps only the mask's alpha, so any opaque token works as the mask color.
    val opaque = HbTheme.colors.textPrimary.copy(alpha = 1f)
    val clear = opaque.copy(alpha = 0f)
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val width = fade.toPx().coerceAtMost(size.width)
            val isRtl = layoutDirection == LayoutDirection.Rtl
            val start = if (isRtl) width else size.width - width
            val end = if (isRtl) 0f else size.width
            drawRect(
                brush = Brush.horizontalGradient(listOf(opaque, clear), startX = start, endX = end),
                blendMode = BlendMode.DstIn,
            )
        }
}
