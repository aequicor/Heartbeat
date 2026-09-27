package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** One bounded payload row with no vertical scroll owner of its own. */
@Composable
internal fun HbToolPayloadRow(
    row: HbToolDisplayRow,
    modifier: Modifier = Modifier,
    labels: HbToolLabels = HbToolLabels(),
    onLinkClick: ((String) -> Unit)? = null,
    isSelectionContainerRequired: Boolean = true,
) {
    if (row.section != null) {
        val label = when (row.section) {
            HbToolSection.Details -> labels.details
            HbToolSection.Console -> labels.console
            HbToolSection.Diff -> labels.diff
        }
        ToolSectionHeading(label = label, modifier = modifier)
    } else if (isSelectionContainerRequired) {
        SelectionContainer(modifier = modifier) {
            ToolPayloadBody(row, labels, onLinkClick)
        }
    } else {
        // Transcript message content already owns selection for this row.
        Box(modifier = modifier) { ToolPayloadBody(row, labels, onLinkClick) }
    }
}

@Composable
private fun ToolSectionHeading(label: String, modifier: Modifier = Modifier) {
    HbColumn(modifier = modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbDivider()
        HbText(
            text = label,
            modifier = Modifier.semantics { heading() },
            style = HbTheme.typography.label,
            color = HbTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun ToolPayloadBody(row: HbToolDisplayRow, labels: HbToolLabels, onLinkClick: ((String) -> Unit)?) {
    when {
        row.markdown != null -> HbMarkdownBlockContent(row.markdown, onLinkClick = onLinkClick)
        row.diff != null -> HbDiffChunkContent(row.diff, labels = labels)
        row.console != null -> HbConsoleContent(row.console)
    }
}

@Immutable
internal data class HbToolDisplayRow(
    val id: String,
    val markdown: HbMarkdownBlock? = null,
    val text: String = "",
    val isDiff: Boolean = false,
    val section: HbToolSection? = null,
    val console: HbConsoleChunk? = null,
    val diff: HbDiffChunk? = null,
) {
    val contentType: String get() = when {
        section != null -> "tool-section:$section"
        markdown != null -> "tool-markdown:${markdown.kind}"
        isDiff -> "tool-diff"
        else -> "tool-console"
    }
}

internal enum class HbToolSection { Details, Console, Diff }

/** Parses once during preparation; Markdown already chunks paragraphs, fences and table cells. */
internal fun prepareToolRows(blocks: ImmutableList<HbToolBlock>): ImmutableList<HbToolDisplayRow> =
    blocks.flatMap { block ->
        val content = when (block) {
            is HbToolBlock.Markdown -> parseHbMarkdown(
                block.source,
            ).map { HbToolDisplayRow(toolRowId(block.id, "markdown:${it.id}"), markdown = it) }

            is HbToolBlock.Console -> chunkHbConsole(block.text).mapIndexed { index, console ->
                HbToolDisplayRow(toolRowId(block.id, "literal:$index"), text = console.text, console = console)
            }

            is HbToolBlock.Diff -> chunkHbDiff(block.text).mapIndexed { index, diff ->
                HbToolDisplayRow(toolRowId(block.id, "literal:$index"), text = diff.text, isDiff = true, diff = diff)
            }
        }
        val section = when (block) {
            is HbToolBlock.Markdown -> HbToolSection.Details
            is HbToolBlock.Console -> HbToolSection.Console
            is HbToolBlock.Diff -> HbToolSection.Diff
        }
        listOf(HbToolDisplayRow(toolRowId(block.id, "section"), section = section)) + content
    }.toImmutableList()

private fun toolRowId(blockId: String, part: String): String = "block:${blockId.length}:$blockId:$part"
