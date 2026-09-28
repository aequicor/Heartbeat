package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.aequicor.heartbeat.ds.theme.HbTheme

/** Theme typography with explicit text, style and color overrides. */
@Composable
public fun HbText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = HbTheme.typography.body,
    color: Color = HbTheme.colors.textPrimary,
    maxLines: Int = Int.MAX_VALUE,
    isOverflowTooltipEnabled: Boolean = false,
) {
    if (isOverflowTooltipEnabled) {
        OverflowTooltipText(text, style.copy(color = color), maxLines, modifier)
        return
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun OverflowTooltipText(text: String, style: TextStyle, maxLines: Int, modifier: Modifier = Modifier) {
    var hasOverflow by remember(text) { mutableStateOf(false) }
    HbTooltip(text, modifier, isEnabled = hasOverflow) {
        BasicText(
            text,
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { hasOverflow = it.hasVisualOverflow },
        )
    }
}
