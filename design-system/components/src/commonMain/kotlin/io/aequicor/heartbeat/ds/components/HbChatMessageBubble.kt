package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Selectable message supporting role, tone, width, placement and complete content replacement.
 * A supplied [content] slot replaces only the body, preserving its author and status header.
 * [contentPadding] lets adjoining prepared payload segments share a continuous reading surface.
 */
@Composable
public fun HbChatMessageBubble(
    message: HbChatMessage,
    modifier: Modifier = Modifier,
    streamingLabel: String = "",
    showHeader: Boolean = true,
    showStatus: Boolean = true,
    toolLabels: HbToolLabels = HbToolLabels(),
    onLinkClick: ((String) -> Unit)? = null,
    contentPadding: PaddingValues? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val palette = tonePalette(if (message.status == HbMessageStatus.Error) HbTone.Danger else message.appearance.tone)
    val isReadingSurface = message.usesReadingSurface()
    val background = message.appearance.background.orElse(
        if (isReadingSurface) HbTheme.colors.assistantSurface else palette.background,
    )
    val foreground = message.appearance.foreground.orElse(palette.foreground)
    val placement = when (resolvedAlignment(message)) {
        HbMessageAlignment.End -> Alignment.TopEnd
        HbMessageAlignment.Center -> Alignment.TopCenter
        HbMessageAlignment.Automatic, HbMessageAlignment.Start -> Alignment.TopStart
    }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = placement) {
        HbColumn(
            modifier = Modifier
                .widthIn(max = HbTheme.dimensions.chatMessageMaxWidth)
                .fillMaxWidth(message.appearance.widthFraction)
                .messageBubbleSurface(background, showHeader, showStatus, isReadingSurface)
                .padding(contentPadding ?: messageBubblePadding(showHeader, showStatus))
                .semantics {
                    if (message.status == HbMessageStatus.Streaming) stateDescription = streamingLabel
                },
            gap = HbTheme.spacing.s,
        ) {
            if (showHeader) MessageHeader(message, foreground)
            MessageContent(message, foreground, toolLabels, onLinkClick, content)
            if (showStatus) MessageStatus(message.status, streamingLabel, foreground)
        }
    }
}

@Composable
@ReadOnlyComposable
private fun messageBubblePadding(hasTop: Boolean, hasBottom: Boolean): PaddingValues = PaddingValues(
    start = HbTheme.spacing.l,
    end = HbTheme.spacing.l,
    top = if (hasTop) HbTheme.spacing.l else HbTheme.elevation.none,
    bottom = if (hasBottom) HbTheme.spacing.l else HbTheme.spacing.s,
)

@Composable
@ReadOnlyComposable
private fun Modifier.messageBubbleSurface(
    background: Color,
    hasTop: Boolean,
    hasBottom: Boolean,
    isReadingSurface: Boolean,
): Modifier {
    if (background.alpha == 0f) return this
    val corners = HbTheme.shapes.medium
    if (hasTop && hasBottom && !isReadingSurface) return hbSurface(background, corners)
    val square = CornerSize(HbTheme.elevation.none)
    val shape = corners.copy(
        topStart = if (hasTop) corners.topStart else square,
        topEnd = if (hasTop) corners.topEnd else square,
        bottomStart = if (hasBottom) corners.bottomStart else square,
        bottomEnd = if (hasBottom) corners.bottomEnd else square,
    )
    return background(background, shape)
}

@Composable
private fun MessageContent(
    message: HbChatMessage,
    foreground: Color,
    labels: HbToolLabels,
    onLinkClick: ((String) -> Unit)?,
    content: (@Composable () -> Unit)?,
) {
    SelectionContainer {
        if (content == null) MessageBody(message, foreground, onLinkClick) else content()
    }
    if (content == null && message.toolCalls.isNotEmpty()) {
        HbColumn {
            message.toolCalls.forEach { tool ->
                key(tool.id) { HbToolCallView(toolCall = tool, labels = labels, onLinkClick = onLinkClick) }
            }
        }
    }
}

@Composable
private fun MessageStatus(status: HbMessageStatus, streamingLabel: String, foreground: Color) {
    if (status != HbMessageStatus.Streaming || streamingLabel.isBlank()) return
    HbText(
        text = streamingLabel,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = HbTheme.typography.caption,
        color = foreground,
    )
}

@Composable
private fun MessageHeader(message: HbChatMessage, foreground: Color) {
    HbFlowRow(gap = HbTheme.spacing.s) {
        HbText(text = message.author, style = HbTheme.typography.label, color = foreground)
        message.label?.let { label ->
            HbText(text = label, style = HbTheme.typography.caption, color = foreground)
        }
    }
}

@Composable
private fun MessageBody(message: HbChatMessage, foreground: Color, onLinkClick: ((String) -> Unit)? = null) {
    when (message.kind) {
        HbMessageKind.Code -> HbScrollableCode(
            text = message.text,
            spans = remember(message.text, message.codeLanguage) {
                highlightHbCode(message.text, message.codeLanguage)
            },
            foreground = foreground,
        )

        HbMessageKind.Tool -> HbText(text = message.text, style = HbTheme.typography.code, color = foreground)

        HbMessageKind.Notice -> HbText(text = message.text, style = HbTheme.typography.caption, color = foreground)

        HbMessageKind.Text -> HbText(text = message.text, color = foreground)

        HbMessageKind.Markdown -> HbMarkdown(source = message.text, foreground = foreground, onLinkClick = onLinkClick)
    }
}

private fun Color.orElse(fallback: Color): Color = if (this == Color.Unspecified) fallback else this

private fun HbChatMessage.usesReadingSurface(): Boolean = role == HbChatRole.Assistant &&
    appearance.tone == HbTone.Neutral && status != HbMessageStatus.Error &&
    appearance.background == Color.Unspecified
