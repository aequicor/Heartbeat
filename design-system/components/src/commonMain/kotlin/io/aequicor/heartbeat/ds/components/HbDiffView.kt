package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Portable unified-diff panel with a selectable, copyable file path and semantic line colors.
 * Has no vertical scroll owner; the chat renders its prepared chunks in the outer lazy list.
 * Uses the same Foundation rendering in Material, Fluent and macOS themes, like Markdown.
 */
@Composable
public fun HbDiffView(
    text: String,
    modifier: Modifier = Modifier,
    labels: HbToolLabels = HbToolLabels(),
    isSelectionContainerRequired: Boolean = true,
) {
    val chunks = remember(text) { chunkHbDiff(text) }
    HbColumn(modifier = modifier.fillMaxWidth(), gap = HbTheme.elevation.none) {
        chunks.forEachIndexed { index, chunk ->
            key(index) { DiffChunk(chunk, index, labels, isSelectionContainerRequired) }
        }
    }
}

/**
 * One chunk of the panel, separated from the chunk above it. The panel owns text selection only when the caller
 * cannot provide a [SelectionContainer] itself.
 */
@Composable
private fun DiffChunk(chunk: HbDiffChunk, index: Int, labels: HbToolLabels, isSelectionRequired: Boolean) {
    val gap = chunkGapAbove(index, chunk)
    if (isSelectionRequired) {
        SelectionContainer { HbDiffChunkContent(chunk, Modifier.padding(top = gap), labels) }
    } else {
        HbDiffChunkContent(chunk, Modifier.padding(top = gap), labels)
    }
}

/** Space above the chunk at [index]: only a chunk that opens a file is separated from the one before it. */
@Composable
@ReadOnlyComposable
private fun chunkGapAbove(index: Int, chunk: HbDiffChunk) =
    if (index > 0 && chunk.isFirst) HbTheme.spacing.s else HbTheme.elevation.none

/** A bounded diff segment; selection is owned by the containing message or standalone panel. */
@Composable
internal fun HbDiffChunkContent(
    chunk: HbDiffChunk,
    modifier: Modifier = Modifier,
    labels: HbToolLabels = HbToolLabels(),
) {
    val shape = diffChunkShape(chunk.isFirst, chunk.isLast)
    HbColumn(
        modifier = modifier.fillMaxWidth().background(HbTheme.colors.assistantSurface, shape),
        gap = HbTheme.elevation.none,
    ) {
        if (chunk.isFirst) HbDiffHeader(chunk.filePath, labels)
        DiffLines(
            chunk,
            modifier = Modifier.padding(
                top = if (chunk.isFirst) HbTheme.spacing.xs else HbTheme.elevation.none,
                bottom = if (chunk.isLast) HbTheme.spacing.xs else HbTheme.elevation.none,
            ),
        )
    }
}

@Composable
@ReadOnlyComposable
private fun diffChunkShape(isFirst: Boolean, isLast: Boolean): RoundedCornerShape {
    val corners = HbTheme.shapes.small
    val square = CornerSize(HbTheme.elevation.none)
    return corners.copy(
        topStart = if (isFirst) corners.topStart else square,
        topEnd = if (isFirst) corners.topEnd else square,
        bottomStart = if (isLast) corners.bottomStart else square,
        bottomEnd = if (isLast) corners.bottomEnd else square,
    )
}

@Composable
private fun DiffLines(chunk: HbDiffChunk, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    val gutter = if (scrollState.maxValue in 1 until Int.MAX_VALUE) HbTheme.spacing.m else HbTheme.elevation.none
    HbBoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        HbColumn(
            modifier = Modifier.hbHorizontalScroll(scrollState).widthIn(min = maxWidth).width(IntrinsicSize.Max)
                .padding(bottom = gutter),
            gap = HbTheme.elevation.none,
        ) {
            chunk.displayText.lineSequence().forEachIndexed { index, line ->
                val tone = chunk.lineTones[index]
                val palette = tonePalette(tone)
                HbText(
                    text = line,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (tone == HbTone.Neutral) HbTheme.colors.assistantSurface else palette.background)
                        .padding(horizontal = HbTheme.spacing.m),
                    style = HbTheme.typography.code,
                    color = palette.foreground,
                )
            }
        }
    }
}
