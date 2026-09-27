package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
