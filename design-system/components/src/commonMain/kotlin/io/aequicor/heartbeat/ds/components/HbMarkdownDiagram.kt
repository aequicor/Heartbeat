package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * One complete diagram fence. Without a diagram renderer the row shows its source like a code fence: a single panel
 * with one horizontal scroll; [MAX_DIAGRAM_LINES] and [MAX_DIAGRAM_CHARACTERS] keep it bounded.
 */
@Composable
internal fun MarkdownDiagram(
    block: HbMarkdownBlock,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
) {
    MarkdownDiagramSource(block, modifier, foreground)
}

@Composable
private fun MarkdownDiagramSource(
    block: HbMarkdownBlock,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
) {
    val spans = remember(block.content.text, block.language) { highlightHbCode(block.content.text, block.language) }
    MarkdownCodePanel(modifier) {
        if (!block.language.isNullOrBlank()) {
            HbText(text = block.language, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
        }
        HbScrollableCode(text = block.content.text, spans = spans, foreground = foreground)
    }
}
