package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/Markdown")

/**
 * Standalone bounded Markdown viewport. Transcripts should prepare [parseHbMarkdown] once and render
 * [HbMarkdownBlockContent] in their own lazy list, avoiding nested scrolling for long agent messages.
 */
@Composable
public fun HbMarkdown(
    source: String,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val blocks = remember(source) { parseHbMarkdown(source) }
    HbLazyColumn(modifier = modifier.heightIn(max = HbTheme.dimensions.toolPayloadMaxHeight), gap = HbTheme.spacing.s) {
        items(blocks, key = { it.id }, contentType = { it.kind }) { block ->
            HbMarkdownBlockContent(block, foreground = foreground, onLinkClick = onLinkClick)
        }
    }
}

/** Renders one bounded, prepared row. Link handling belongs exclusively to the caller. */
@Composable
public fun HbMarkdownBlockContent(
    block: HbMarkdownBlock,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
    onLinkClick: ((String) -> Unit)? = null,
) {
    when (block.kind) {
        HbMarkdownBlockKind.Code -> MarkdownCode(block, modifier, foreground)

        HbMarkdownBlockKind.TableRow -> MarkdownTableRow(block, modifier, foreground, onLinkClick)

        HbMarkdownBlockKind.Rule -> Box(
            modifier = modifier.fillMaxWidth().height(
                HbTheme.dimensions.borderWidth,
            ).background(HbTheme.colors.outlineSubtle),
        )

        HbMarkdownBlockKind.Heading -> MarkdownHeading(block, modifier, foreground, onLinkClick)

        HbMarkdownBlockKind.Paragraph, HbMarkdownBlockKind.Quote -> MarkdownParagraph(
            block,
            modifier,
            foreground,
            onLinkClick,
        )
    }
}

@Composable
private fun MarkdownCode(
    block: HbMarkdownBlock,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
) {
    val spans = remember(block.content.text, block.language, block.codeSpans) {
        block.codeSpans ?: highlightHbCode(block.content.text, block.language)
    }
    HbColumn(
        modifier = modifier.fillMaxWidth().background(HbTheme.colors.surfaceElevated, HbTheme.shapes.small)
            .padding(HbTheme.spacing.m),
        gap = HbTheme.spacing.xs,
    ) {
        if (!block.language.isNullOrBlank()) {
            HbText(text = block.language, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
        }
        HbScrollableCode(
            text = block.content.text,
            spans = spans,
            foreground = foreground,
        )
    }
}

@Composable
private fun MarkdownHeading(
    block: HbMarkdownBlock,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val style = when (block.level) {
        1 -> HbTheme.typography.title
        2 -> HbTheme.typography.body.copy(fontWeight = FontWeight.Bold)
        else -> HbTheme.typography.label
    }
    MarkdownRichText(block.content, modifier.semantics { heading() }, style, foreground, onLinkClick)
}

@Composable
private fun MarkdownParagraph(
    block: HbMarkdownBlock,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val quoteModifier = if (block.kind == HbMarkdownBlockKind.Quote) {
        Modifier.border(HbTheme.dimensions.borderWidth, HbTheme.colors.outlineSubtle, HbTheme.shapes.small)
            .padding(HbTheme.spacing.m)
    } else {
        Modifier
    }
    HbRow(
        modifier = modifier.padding(start = HbTheme.spacing.l * block.level.coerceAtMost(4)).then(quoteModifier),
        gap = HbTheme.spacing.s,
    ) {
        block.marker?.let { HbText(text = it, color = foreground) }
        MarkdownRichText(block.content, Modifier.weight(1f), HbTheme.typography.body, foreground, onLinkClick)
    }
}

@Composable
private fun MarkdownTableRow(
    block: HbMarkdownBlock,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
    onLinkClick: ((String) -> Unit)? = null,
) {
    HbRow(modifier = modifier.hbHorizontalScroll(rememberScrollState()), gap = HbTheme.spacing.xxs) {
        block.cells.forEach { cell ->
            MarkdownRichText(
                content = cell,
                modifier = Modifier.width(HbTheme.dimensions.markdownTableCellWidth)
                    .background(
                        if (block.isTableHeader) HbTheme.colors.primaryContainer else HbTheme.colors.surfaceElevated,
                    )
                    .padding(HbTheme.spacing.m),
                style = if (block.isTableHeader) HbTheme.typography.label else HbTheme.typography.body,
                foreground = foreground,
                onLinkClick = onLinkClick,
            )
        }
    }
}

@Composable
private fun MarkdownRichText(
    content: HbMarkdownText,
    modifier: Modifier = Modifier,
    style: TextStyle = HbTheme.typography.body,
    foreground: Color = HbTheme.colors.textPrimary,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val codeStyle = HbTheme.typography.code.toSpanStyle().copy(background = HbTheme.colors.primaryContainer)
    val annotated = remember(content, codeStyle, onLinkClick) {
        annotatedMarkdown(content, codeStyle, onLinkClick)
    }
    BasicText(text = annotated, modifier = modifier, style = style.copy(color = foreground))
}

private fun annotatedMarkdown(
    content: HbMarkdownText,
    codeStyle: SpanStyle,
    onLinkClick: ((String) -> Unit)?,
): AnnotatedString = buildAnnotatedString {
    append(content.text)
    content.spans.forEach { span ->
        val style = when (span.style) {
            HbMarkdownStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
            HbMarkdownStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
            HbMarkdownStyle.Code -> codeStyle
            HbMarkdownStyle.Strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            HbMarkdownStyle.Link -> SpanStyle(textDecoration = TextDecoration.Underline)
        }
        addStyle(style, span.start, span.end)
        if (span.style == HbMarkdownStyle.Link && span.destination != null && onLinkClick != null) {
            addLink(
                LinkAnnotation.Clickable(
                    tag = span.destination,
                    styles = TextLinkStyles(style = style),
                    linkInteractionListener = {
                        log.i { "Markdown link activated" }
                        onLinkClick(span.destination)
                    },
                ),
                span.start,
                span.end,
            )
        }
    }
}
