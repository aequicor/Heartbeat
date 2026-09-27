package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.ImmutableList

/** Keeps a horizontal scrollbar below the last code line without adding space to fitting content. */
@Composable
internal fun HbScrollableCode(
    text: String,
    spans: ImmutableList<HbCodeSpan>,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
) {
    val scrollState = rememberScrollState()
    val gutter = if (scrollState.maxValue in 1 until Int.MAX_VALUE) HbTheme.spacing.m else HbTheme.elevation.none
    HbCodeText(
        text = text,
        spans = spans,
        modifier = modifier.hbHorizontalScroll(scrollState).padding(bottom = gutter),
        foreground = foreground,
    )
}
