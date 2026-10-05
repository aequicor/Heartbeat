package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

@Immutable
internal sealed interface HbTranscriptBody {
    data object Embedded : HbTranscriptBody
    data class Text(
        val text: String,
        val kind: HbMessageKind,
        val codeSpans: ImmutableList<HbCodeSpan> = persistentListOf(),
    ) : HbTranscriptBody
    data class Markdown(val block: HbMarkdownBlock) : HbTranscriptBody
    data class Tool(val call: HbToolCall, val rows: ImmutableList<HbToolDisplayRow>) : HbTranscriptBody
    data class ToolPayload(val row: HbToolDisplayRow, val isFirst: Boolean = false, val isLast: Boolean = false) :
        HbTranscriptBody
}

@Immutable
internal data class HbTranscriptChunk(
    val messageId: String,
    val id: String,
    val body: HbTranscriptBody,
    val isFirst: Boolean = false,
    val isLast: Boolean = false,
) {
    val key: String get() = "message:${messageId.length}:$messageId:$id"
    val contentType: String get() = when (val current = body) {
        HbTranscriptBody.Embedded -> "embedded"
        is HbTranscriptBody.Text -> "text:${current.kind}"
        is HbTranscriptBody.Markdown -> "markdown:${current.block.kind}"
        is HbTranscriptBody.Tool -> "tool"
        is HbTranscriptBody.ToolPayload -> "tool-payload:${current.row.contentType}"
    }
}

internal fun transcriptChunks(
    message: HbChatMessage,
    previousChunks: List<HbTranscriptChunk> = emptyList(),
): PersistentList<HbTranscriptChunk> {
    val chunks = mutableListOf<HbTranscriptChunk>()
    if (message.parts.isEmpty()) {
        appendMessageText(message, chunks)
        appendToolChunks(message, previousChunks, chunks)
    } else {
        val previousTools = previousChunks.mapNotNull { it.body as? HbTranscriptBody.Tool }.associateBy { it.call.id }
        message.parts.forEach { part ->
            when (part) {
                is HbMessagePart.Text -> {
                    val textChunks = mutableListOf<HbTranscriptChunk>()
                    appendMessageText(message.copy(text = part.text, kind = part.kind), textChunks)
                    chunks += textChunks.map { it.copy(id = "part:${part.id.length}:${part.id}:${it.id}") }
                }

                is HbMessagePart.Tool -> chunks += toolChunk(message.id, part.call, previousTools[part.id])
            }
        }
    }
    if (message.hasEmbeddedContent) chunks.add(HbTranscriptChunk(message.id, "embedded", HbTranscriptBody.Embedded))
    if (chunks.isEmpty()) {
        chunks.add(HbTranscriptChunk(message.id, "empty", HbTranscriptBody.Text("", message.kind)))
    }
    chunks[0] = chunks.first().copy(isFirst = true)
    chunks[chunks.lastIndex] = chunks.last().copy(isLast = true)
    return chunks.toPersistentList()
}

private fun appendMessageText(message: HbChatMessage, chunks: MutableList<HbTranscriptChunk>) {
    when (message.kind) {
        HbMessageKind.Markdown -> parseHbMarkdown(message.text).forEach { block ->
            chunks.add(HbTranscriptChunk(message.id, "markdown:${block.id}", HbTranscriptBody.Markdown(block)))
        }

        HbMessageKind.Code -> chunkHbCode(message.text, message.codeLanguage).forEachIndexed { index, code ->
            chunks.add(
                HbTranscriptChunk(
                    message.id,
                    "code:$index",
                    HbTranscriptBody.Text(code.text, message.kind, code.spans),
                ),
            )
        }

        HbMessageKind.Text, HbMessageKind.Tool, HbMessageKind.Notice -> appendTextChunks(message, chunks)
    }
}

private fun appendToolChunks(
    message: HbChatMessage,
    previousChunks: List<HbTranscriptChunk>,
    chunks: MutableList<HbTranscriptChunk>,
) {
    val previousTools = previousChunks.mapNotNull { it.body as? HbTranscriptBody.Tool }.associateBy { it.call.id }
    message.toolCalls.forEach { call -> chunks += toolChunk(message.id, call, previousTools[call.id]) }
}

private fun toolChunk(messageId: String, call: HbToolCall, previous: HbTranscriptBody.Tool?): HbTranscriptChunk {
    val rows = previous?.takeIf { it.call.blocks == call.blocks }?.rows ?: prepareToolRows(call.blocks)
    return HbTranscriptChunk(messageId, "tool:${call.id}", HbTranscriptBody.Tool(call, rows))
}

/** Shares the bounded, CRLF- and surrogate-safe splitter used by Markdown, code, console and diff rows. */
private fun appendTextChunks(message: HbChatMessage, chunks: MutableList<HbTranscriptChunk>) {
    var start = 0
    chunkHbText(HbMarkdownText(message.text)).forEach { chunk ->
        if (chunk.text.isNotEmpty()) {
            chunks.add(HbTranscriptChunk(message.id, "text:$start", HbTranscriptBody.Text(chunk.text, message.kind)))
        }
        start += chunk.text.length
    }
}

@Composable
internal fun HbTranscriptChunkContent(
    chunk: HbTranscriptChunk,
    message: HbChatMessage,
    streamingLabel: String,
    toolLabels: HbToolLabels,
    onLinkClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    isToolExpanded: Boolean = false,
    onToolExpandedChange: (Boolean) -> Unit = {},
    onToolAction: (HbToolCall, HbToolAction) -> Unit = { _, _ -> },
    embeddedContent: (@Composable () -> Unit)? = null,
) {
    val palette = tonePalette(if (message.status == HbMessageStatus.Error) HbTone.Danger else message.appearance.tone)
    val foreground = if (message.appearance.foreground == Color.Unspecified) {
        palette.foreground
    } else {
        message.appearance.foreground
    }
    HbChatMessageBubble(
        message = if (chunk.isFirst && chunk.isLast) {
            message
        } else {
            message.copy(
                appearance = message.appearance.copy(isContentWidth = false),
            )
        },
        modifier = modifier,
        streamingLabel = streamingLabel,
        showHeader = chunk.isFirst,
        showStatus = chunk.isLast,
        onLinkClick = onLinkClick,
        toolLabels = toolLabels,
        contentPadding = if (message.appearance.isUnified) {
            unifiedChunkPadding(chunk, isHostEntry = message.isHostEntry)
        } else {
            toolPanelChunkPadding(chunk)
        },
        content = chunk.takeUnless { it.isEmptyUserMessage(message) }?.let {
            {
                when (val body = chunk.body) {
                    HbTranscriptBody.Embedded -> embeddedContent?.invoke()

                    is HbTranscriptBody.Markdown -> HbMarkdownBlockContent(
                        body.block,
                        foreground = foreground,
                        onLinkClick = onLinkClick,
                    )

                    is HbTranscriptBody.Tool -> HbToolCallHeader(
                        toolCall = body.call,
                        isExpanded = isToolExpanded,
                        onExpandedChange = onToolExpandedChange,
                        labels = toolLabels,
                        isUnified = message.appearance.isUnified,
                        onAction = { onToolAction(body.call, it) },
                    )

                    is HbTranscriptBody.ToolPayload -> UnifiedToolPayload(
                        body = body,
                        isUnified = message.appearance.isUnified,
                        labels = toolLabels,
                        onLinkClick = onLinkClick,
                    )

                    is HbTranscriptBody.Text -> TranscriptText(body, foreground, isLastSegment = chunk.isLast)
                }
            }
        },
    )
}

@Composable
@ReadOnlyComposable
private fun toolPanelChunkPadding(chunk: HbTranscriptChunk): PaddingValues? {
    val markdown = (chunk.body as? HbTranscriptBody.Markdown)?.block
    if (markdown != null) return codeSegmentPadding(chunk, markdown)
    val row = (chunk.body as? HbTranscriptBody.ToolPayload)?.row ?: return null
    val isLast = row.console?.isLast ?: row.diff?.isLast ?: return null
    return PaddingValues(
        start = HbTheme.spacing.l,
        end = HbTheme.spacing.l,
        top = if (chunk.isFirst) HbTheme.spacing.l else HbTheme.elevation.none,
        bottom = when {
            chunk.isLast -> HbTheme.spacing.l
            isLast -> HbTheme.spacing.s
            else -> HbTheme.elevation.none
        },
    )
}

/** Continued fence segments join without the bubble's row gap so a long block stays one panel. */
@Composable
@ReadOnlyComposable
private fun codeSegmentPadding(chunk: HbTranscriptChunk, block: HbMarkdownBlock): PaddingValues? {
    if (block.kind != HbMarkdownBlockKind.Code || block.isLastSegment) return null
    return PaddingValues(
        start = HbTheme.spacing.l,
        end = HbTheme.spacing.l,
        top = if (chunk.isFirst) HbTheme.spacing.l else HbTheme.elevation.none,
        bottom = HbTheme.elevation.none,
    )
}

@Composable
private fun TranscriptText(body: HbTranscriptBody.Text, foreground: Color, isLastSegment: Boolean) {
    val isCode = body.kind == HbMessageKind.Code || body.kind == HbMessageKind.Tool
    if (isCode) {
        HbScrollableCode(
            text = body.text,
            spans = body.codeSpans,
            foreground = foreground,
        )
    } else {
        HbText(
            // The next row starts on the following line; a kept boundary newline would draw an empty line.
            text = if (isLastSegment) body.text else body.text.withoutTrailingLineBreak(),
            style = if (body.kind == HbMessageKind.Notice) HbTheme.typography.caption else HbTheme.typography.body,
            color = foreground,
        )
    }
}

/**
 * Each lazy segment paints the same outer surface; only content gets an internal gap. A host entry has no
 * surface of its own, so it starts right below the previous message's gap.
 */
@Composable
@ReadOnlyComposable
private fun unifiedChunkPadding(chunk: HbTranscriptChunk, isHostEntry: Boolean): PaddingValues {
    val inset = HbTheme.dimensions.messagePadding
    val isPayload = chunk.body is HbTranscriptBody.ToolPayload
    val markdown = (chunk.body as? HbTranscriptBody.Markdown)?.block
    val isMarkdownContinuation = markdown != null && !markdown.isFirstSegment
    return PaddingValues(
        start = inset,
        end = inset,
        top = when {
            chunk.isFirst && isHostEntry -> HbTheme.spacing.none
            chunk.isFirst -> inset
            isPayload || isMarkdownContinuation -> HbTheme.spacing.none
            else -> HbTheme.spacing.l
        },
        bottom = if (chunk.isLast) inset else HbTheme.spacing.none,
    )
}

private fun HbTranscriptChunk.isEmptyUserMessage(message: HbChatMessage): Boolean =
    isFirst && isLast && message.role == HbChatRole.User && (body as? HbTranscriptBody.Text)?.text?.isBlank() == true
