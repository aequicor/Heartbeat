package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

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
    // A bounded chunk keeps its boundary newline for lossless joining; drawing it would add an empty line.
    val displayText = remember(text) { text.withoutTrailingLineBreak() }
    val displaySpans = remember(spans, displayText) { spans.clippedTo(displayText.length) }
    HbCodeText(
        text = displayText,
        spans = displaySpans,
        modifier = modifier.hbHorizontalScroll(scrollState).padding(bottom = gutter),
        foreground = foreground,
    )
}

internal fun String.withoutTrailingLineBreak(): String = when {
    endsWith("\r\n") -> dropLast(2)
    endsWith('\n') || endsWith('\r') -> dropLast(1)
    else -> this
}

private fun ImmutableList<HbCodeSpan>.clippedTo(length: Int): ImmutableList<HbCodeSpan> {
    if (all { it.end <= length }) return this
    return mapNotNull { span ->
        if (span.start >= length) null else span.copy(end = minOf(span.end, length))
    }.toImmutableList()
}
