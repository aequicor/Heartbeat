package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors

/** Console surfaces stay fixed while long text scrolls; middle segments join without rounded seams. */
@Composable
internal fun HbConsoleContent(chunk: HbConsoleChunk, modifier: Modifier = Modifier) {
    val colors = HbTheme.colors
    val annotated = remember(chunk, colors) { annotatedConsole(chunk, colors) }
    val corners = HbTheme.shapes.small
    val square = CornerSize(HbTheme.elevation.none)
    val shape = corners.copy(
        topStart = if (chunk.isFirst) corners.topStart else square,
        topEnd = if (chunk.isFirst) corners.topEnd else square,
        bottomStart = if (chunk.isLast) corners.bottomStart else square,
        bottomEnd = if (chunk.isLast) corners.bottomEnd else square,
    )
    val scrollState = rememberScrollState()
    val scrollbarGutter = if (scrollState.maxValue in 1 until Int.MAX_VALUE) {
        HbTheme.spacing.m
    } else {
        HbTheme.elevation.none
    }
    Box(
        modifier = modifier.fillMaxWidth().background(colors.consoleSurface, shape)
            .padding(
                start = HbTheme.spacing.m,
                end = HbTheme.spacing.m,
                top = if (chunk.isFirst) HbTheme.spacing.m else HbTheme.elevation.none,
                bottom = if (chunk.isLast) HbTheme.spacing.m else HbTheme.elevation.none,
            ),
    ) {
        BasicText(
            text = annotated,
            modifier = Modifier.hbHorizontalScroll(scrollState, thumbColor = colors.consoleMuted)
                .padding(bottom = scrollbarGutter),
            style = HbTheme.typography.code.copy(color = colors.consoleText),
        )
    }
}

private fun annotatedConsole(chunk: HbConsoleChunk, colors: HbColors): AnnotatedString = buildAnnotatedString {
    append(chunk.displayText)
    chunk.spans.forEach { span ->
        val color = when (span.tone) {
            HbConsoleTone.Command -> colors.consoleCommand
            HbConsoleTone.Info -> colors.consoleInfo
            HbConsoleTone.Success -> colors.consoleSuccess
            HbConsoleTone.Warning -> colors.consoleWarning
            HbConsoleTone.Error -> colors.consoleError
        }
        val end = minOf(span.end, chunk.displayText.length)
        if (span.start < end) addStyle(SpanStyle(color = color), span.start, end)
    }
}
