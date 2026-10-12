package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.layouts.hbScrollbars
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Foundation grid shared by all platform kits. One scroll owner moves the header and every body row.
 * Row backgrounds span the tallest cell. Large tables use bounded, lazy rows rather than an unbounded layout.
 * Standard Markdown bold, italic, code, strike and links remain available to agent-authored cells.
 */
@Composable
internal fun MarkdownTable(
    block: HbMarkdownBlock,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val rows = block.tableRows
    val columnCount = rows.maxOfOrNull { it.cells.size } ?: return
    if (columnCount == 0) return
    val widths = tableColumnWidths(rows, columnCount)
    val scroll = rememberScrollState()
    val verticalScroll = rememberLazyListState()
    val line = HbTheme.colors.outlineSubtle
    val lineWidth = HbTheme.dimensions.borderWidth
    val isLarge = rows.isTableViewportRequired
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val naturalWidth = widths.reduce { sum, width -> sum + width }
        val extra = if (constraints.hasBoundedWidth && maxWidth > naturalWidth) {
            maxWidth - naturalWidth
        } else {
            HbTheme.elevation.none
        }
        val widestColumn = widths.indices.maxBy { widths[it] }
        val gridWidths = widths.mapIndexed { index, width ->
            if (index == widestColumn) width + extra else width
        }.toImmutableList()
        val viewportModifier = if (isLarge) Modifier.hbScrollbars(verticalScroll) else Modifier
        val gridModifier = viewportModifier.hbHorizontalScroll(scroll).border(lineWidth, line)
            .semantics { collectionInfo = CollectionInfo(rows.last().index + 1, columnCount) }
        if (isLarge) {
            Box(modifier = gridModifier) {
                HbLazyColumn(
                    modifier = Modifier.width(naturalWidth + extra)
                        .heightIn(max = HbTheme.dimensions.markdownTableMaxHeight),
                    state = verticalScroll,
                    showScrollbar = false,
                    contentPadding = PaddingValues(HbTheme.elevation.none),
                    gap = HbTheme.elevation.none,
                ) {
                    items(rows.size) { index ->
                        SelectionContainer {
                            MarkdownTableRow(rows[index], gridWidths, foreground, onLinkClick)
                        }
                    }
                }
            }
        } else {
            Column(modifier = gridModifier) {
                rows.forEach { row -> MarkdownTableRow(row, gridWidths, foreground, onLinkClick) }
            }
        }
    }
}

/** Samples a bounded prefix, so streaming a huge table does not remeasure its entire history. */
@Composable
private fun tableColumnWidths(rows: ImmutableList<HbMarkdownTableRow>, columnCount: Int): ImmutableList<Dp> {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = HbTheme.typography.body
    val headerStyle = style.copy(fontWeight = FontWeight.SemiBold)
    val emphasis = if (HbTheme.dimensions.isDesktop) FontWeight.SemiBold else FontWeight.Bold
    val codeStyle = HbTheme.typography.code.toSpanStyle()
    val padding = HbTheme.spacing.m
    val minWidth = HbTheme.dimensions.markdownTableMinCellWidth
    val maxWidth = HbTheme.dimensions.markdownTableCellWidth
    val sample = rows.take(INLINE_TABLE_ROWS)
    return remember(sample, columnCount, measurer, density, style, codeStyle, emphasis, padding, minWidth, maxWidth) {
        with(density) {
            List(columnCount) { column ->
                val textWidth = sample.maxOf { row ->
                    measurer.measure(
                        text = annotatedMarkdown(
                            row.cells.getOrNull(column) ?: HbMarkdownText(""),
                            codeStyle,
                            emphasis,
                            null,
                        ),
                        style = if (row.index == 0) headerStyle else style,
                        softWrap = false,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = (maxWidth - padding * 2).roundToPx()),
                    ).size.width.toDp()
                }
                (textWidth + padding * 2).coerceIn(minWidth, maxWidth)
            }.toImmutableList()
        }
    }
}

@Composable
private fun MarkdownTableRow(
    row: HbMarkdownTableRow,
    widths: ImmutableList<Dp>,
    foreground: Color,
    onLinkClick: ((String) -> Unit)?,
) {
    val line = HbTheme.colors.outlineSubtle
    val lineWidth = HbTheme.dimensions.borderWidth
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val background = if (row.index % 2 == 0) HbTheme.colors.surfaceElevated else HbTheme.colors.surface
    val style = HbTheme.typography.body.let { if (row.index == 0) it.copy(fontWeight = FontWeight.SemiBold) else it }
    Row(
        modifier = Modifier.height(IntrinsicSize.Min).background(background).drawBehind {
            if (row.index > 0 && row.isFirstSegment) {
                drawLine(line, Offset.Zero, Offset(size.width, 0f), lineWidth.toPx())
            }
        },
    ) {
        widths.forEachIndexed { column, width ->
            MarkdownRichText(
                content = row.cells.getOrNull(column) ?: HbMarkdownText(""),
                modifier = Modifier.width(width).fillMaxHeight()
                    .tableCellDivider(column, isRtl, line, lineWidth)
                    .padding(HbTheme.spacing.m).semantics {
                        collectionItemInfo = CollectionItemInfo(row.index, 1, column, 1)
                        if (row.index == 0) heading()
                    },
                style = style,
                foreground = foreground,
                onLinkClick = onLinkClick,
            )
        }
    }
}

private fun Modifier.tableCellDivider(column: Int, isRtl: Boolean, color: Color, width: Dp): Modifier = drawBehind {
    if (column > 0) {
        val x = if (isRtl) size.width else 0f
        drawLine(color, Offset(x, 0f), Offset(x, size.height), width.toPx())
    }
}

private val ImmutableList<HbMarkdownTableRow>.isTableViewportRequired: Boolean
    get() = size > INLINE_TABLE_ROWS || any { !it.isFirstSegment }

private const val INLINE_TABLE_ROWS = 24
