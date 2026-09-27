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

private const val MAX_TEXT_CHUNK_CHARACTERS = 2048
private const val MAX_TEXT_CHUNK_LINES = 32

@Immutable
internal sealed interface HbTranscriptBody {
    data class Text(
        val text: String,
        val kind: HbMessageKind,
        val codeSpans: ImmutableList<HbCodeSpan> = persistentListOf(),
    ) : HbTranscriptBody
    data class Markdown(val block: HbMarkdownBlock) : HbTranscriptBody
    data class Tool(val call: HbToolCall, val rows: ImmutableList<HbToolDisplayRow>) : HbTranscriptBody
    data class ToolPayload(val row: HbToolDisplayRow) : HbTranscriptBody
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
    if (message.kind == HbMessageKind.Markdown) {
        parseHbMarkdown(message.text).forEach { block ->
            chunks.add(HbTranscriptChunk(message.id, "markdown:${block.id}", HbTranscriptBody.Markdown(block)))
        }
    } else if (message.kind == HbMessageKind.Code) {
        chunkHbCode(message.text, message.codeLanguage).forEachIndexed { index, code ->
            chunks.add(
                HbTranscriptChunk(
                    message.id,
                    "code:$index",
                    HbTranscriptBody.Text(code.text, message.kind, code.spans),
                ),
            )
        }
    } else {
        appendTextChunks(message, chunks)
    }
    appendToolChunks(message, previousChunks, chunks)
    if (chunks.isEmpty()) {
        chunks.add(HbTranscriptChunk(message.id, "empty", HbTranscriptBody.Text("", message.kind)))
    }
    chunks[0] = chunks.first().copy(isFirst = true)
    chunks[chunks.lastIndex] = chunks.last().copy(isLast = true)
    return chunks.toPersistentList()
}

private fun appendToolChunks(
    message: HbChatMessage,
    previousChunks: List<HbTranscriptChunk>,
    chunks: MutableList<HbTranscriptChunk>,
) {
    if (message.toolCalls.isEmpty()) return
    val previousTools = previousChunks.mapNotNull { it.body as? HbTranscriptBody.Tool }.associateBy { it.call.id }
    message.toolCalls.forEach { call ->
        val previous = previousTools[call.id]
        val rows = previous?.takeIf { it.call.blocks == call.blocks }?.rows ?: prepareToolRows(call.blocks)
        chunks.add(HbTranscriptChunk(message.id, "tool:${call.id}", HbTranscriptBody.Tool(call, rows)))
    }
}

private fun appendTextChunks(message: HbChatMessage, chunks: MutableList<HbTranscriptChunk>) {
    var start = 0
    while (start < message.text.length) {
        var end = minOf(start + MAX_TEXT_CHUNK_CHARACTERS, message.text.length)
        var lines = 0
        for (index in start until end) {
            if (message.text[index] == '\n') lines++
            if (lines >= MAX_TEXT_CHUNK_LINES) {
                end = index + 1
                break
            }
        }
        if (end < message.text.length && message.text[end - 1].isHighSurrogate()) end--
        chunks.add(
            HbTranscriptChunk(
                message.id,
                "text:$start",
                HbTranscriptBody.Text(message.text.substring(start, end), message.kind),
            ),
        )
        start = end
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
) {
    val palette = tonePalette(if (message.status == HbMessageStatus.Error) HbTone.Danger else message.appearance.tone)
    val foreground = if (message.appearance.foreground == Color.Unspecified) {
        palette.foreground
    } else {
        message.appearance.foreground
    }
    HbChatMessageBubble(
        message = message,
        modifier = modifier,
        streamingLabel = streamingLabel,
        showHeader = chunk.isFirst,
        showStatus = chunk.isLast,
        onLinkClick = onLinkClick,
        toolLabels = toolLabels,
        contentPadding = toolPanelChunkPadding(chunk),
    ) {
        when (val body = chunk.body) {
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
            )

            is HbTranscriptBody.ToolPayload -> HbToolPayloadRow(
                row = body.row,
                labels = toolLabels,
                onLinkClick = onLinkClick,
                isSelectionContainerRequired = false,
            )

            is HbTranscriptBody.Text -> TranscriptText(body, foreground)
        }
    }
}

@Composable
@ReadOnlyComposable
private fun toolPanelChunkPadding(chunk: HbTranscriptChunk): PaddingValues? {
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

@Composable
private fun TranscriptText(body: HbTranscriptBody.Text, foreground: Color) {
    val isCode = body.kind == HbMessageKind.Code || body.kind == HbMessageKind.Tool
    if (isCode) {
        HbScrollableCode(
            text = body.text,
            spans = body.codeSpans,
            foreground = foreground,
        )
    } else {
        HbText(
            text = body.text,
            style = if (body.kind == HbMessageKind.Notice) HbTheme.typography.caption else HbTheme.typography.body,
            color = foreground,
        )
    }
}
