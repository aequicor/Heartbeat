package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Quiet boundary for a functional region, shared by every platform kit.
 * The caller owns padding and scrolling; the panel does not clip child shadows or add another scroll owner.
 */
@Composable
public fun HbPanel(
    modifier: Modifier = Modifier,
    background: Color = HbTheme.colors.surface,
    shape: Shape = HbTheme.shapes.medium,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.background(background, shape)
            .border(HbTheme.dimensions.borderWidth, HbTheme.colors.outlineSubtle, shape),
        content = content,
    )
}

/** Decorative boundary between related controls and content, without extra spacing or semantics. */
@Composable
public fun HbDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(HbTheme.dimensions.borderWidth)
            .background(HbTheme.colors.outlineSubtle),
    )
}
