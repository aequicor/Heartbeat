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
    MessageBubblePlacement(message, showHeader, modifier) {
        HbColumn(
            modifier = message.bubbleWidth()
                .messageBubbleDecoration(message, background, showHeader, showStatus, isReadingSurface, contentPadding)
                .semantics {
                    if (message.status == HbMessageStatus.Streaming) stateDescription = streamingLabel
                },
            gap = HbTheme.spacing.s,
        ) {
            BubbleBody(message, foreground, streamingLabel, showHeader, showStatus, toolLabels, onLinkClick, content)
        }
    }
}

@Composable
private fun MessageBubblePlacement(
    message: HbChatMessage,
    showHeader: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val placement = message.bubblePlacement()
    val maxWidth = if (message.appearance.isUnified) {
        HbTheme.studioDimensions.messageMaxWidth
    } else {
        HbTheme.dimensions.chatMessageMaxWidth
    }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = placement) {
        Box(
            Modifier.widthIn(max = maxWidth).fillMaxWidth(message.appearance.widthFraction),
            contentAlignment = placement,
        ) {
            HbColumn(
                modifier = message.bubbleWidth(),
                gap = HbTheme.spacing.xs,
                horizontalAlignment = if (placement == Alignment.TopEnd) Alignment.End else Alignment.Start,
            ) {
                MessageOutsideLabel(message, showHeader)
                content()
            }
        }
    }
}

@Composable
private fun MessageOutsideLabel(message: HbChatMessage, showHeader: Boolean) {
    if (!showHeader || message.appearance.isAuthorVisible) return
    message.label?.let { label ->
        HbText(label, style = HbTheme.typography.metadata, color = HbTheme.colors.textSecondary)
    }
}

@Composable
private fun Modifier.messageBubbleDecoration(
    message: HbChatMessage,
    background: Color,
    showHeader: Boolean,
    showStatus: Boolean,
    isReadingSurface: Boolean,
    contentPadding: PaddingValues?,
): Modifier {
    val isUnified = message.appearance.isUnified
    val surface = if (isUnified) {
        hbUnifiedMessageSurface(background, showHeader, showStatus)
    } else {
        messageBubbleSurface(background, showHeader, showStatus, isReadingSurface)
    }
    val padding = contentPadding ?: if (isUnified) {
        PaddingValues(HbTheme.studioDimensions.messagePadding)
    } else {
        messageBubblePadding(showHeader, showStatus)
    }
    return surface.padding(padding)
}

private fun HbChatMessage.bubbleWidth(): Modifier = if (appearance.isContentWidth) Modifier else Modifier.fillMaxWidth()

private fun HbChatMessage.bubblePlacement(): Alignment = when (resolvedAlignment(this)) {
    HbMessageAlignment.End -> Alignment.TopEnd
    HbMessageAlignment.Center -> Alignment.TopCenter
    HbMessageAlignment.Automatic, HbMessageAlignment.Start -> Alignment.TopStart
}

@Composable
private fun BubbleBody(
    message: HbChatMessage,
    foreground: Color,
    streamingLabel: String,
    showHeader: Boolean,
    showStatus: Boolean,
    toolLabels: HbToolLabels,
    onLinkClick: ((String) -> Unit)?,
    content: (@Composable () -> Unit)?,
) {
    val bodyModifier = if (message.appearance.isUnified) Modifier.hbUnifiedBodyIndent() else Modifier
    HbColumn(gap = HbTheme.spacing.s) {
        if (showHeader && message.appearance.isAuthorVisible) {
            if (message.appearance.isUnified) HbUnifiedMessageHeader(message) else MessageHeader(message, foreground)
        }
        HbColumn(bodyModifier, gap = HbTheme.spacing.s) {
            MessageContent(message, foreground, toolLabels, onLinkClick, content)
            MessageFooter(message, showStatus, streamingLabel, toolLabels, foreground)
        }
    }
}

@Composable
private fun MessageFooter(
    message: HbChatMessage,
    showStatus: Boolean,
    streamingLabel: String,
    labels: HbToolLabels,
    foreground: Color,
) {
    if (!showStatus) return
    if (message.appearance.isUnified) {
        HbUnifiedMessageFooter(message, streamingLabel, labels)
    } else {
        MessageStatus(message.status, streamingLabel, foreground)
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
        when {
            content != null -> content()

            message.parts.isNotEmpty() -> HbColumn {
                message.parts.forEach { part ->
                    key(part.id) {
                        when (part) {
                            is HbMessagePart.Text -> MessageBody(
                                message.copy(text = part.text, kind = part.kind),
                                foreground,
                                onLinkClick,
                            )

                            is HbMessagePart.Tool -> HbToolCallView(
                                part.call,
                                labels = labels,
                                onLinkClick = onLinkClick,
                            )
                        }
                    }
                }
            }

            else -> MessageBody(message, foreground, onLinkClick)
        }
    }
    if (content == null && message.parts.isEmpty() && message.toolCalls.isNotEmpty()) {
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
            HbText(text = label, style = HbTheme.typography.metadata, color = foreground)
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
