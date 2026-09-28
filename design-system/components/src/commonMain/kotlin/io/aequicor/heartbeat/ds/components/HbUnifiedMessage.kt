package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.offset
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val messageLog = Log.tag("DS/Message")

/** Indents measured wide content to the author while keeping narrow replies fully usable. */
@Composable
internal fun Modifier.hbUnifiedBodyIndent(): Modifier {
    val inset = HbTheme.studioDimensions.headerAvatarSize + HbTheme.spacing.l
    val minWidth = HbTheme.studioDimensions.messageBodyIndentMinWidth
    return layout { measurable, constraints ->
        val insetPx = if (constraints.maxWidth >= minWidth.roundToPx()) inset.roundToPx() else 0
        val placeable = measurable.measure(constraints.offset(horizontal = -insetPx))
        layout(placeable.width + insetPx, placeable.height) {
            placeable.placeRelative(insetPx, 0)
        }
    }
}

/** Draws only the perimeter belonging to this lazy segment, never a line between adjacent segments. */
@Composable
internal fun Modifier.hbUnifiedMessageSurface(background: Color, hasTop: Boolean, hasBottom: Boolean): Modifier {
    val radius = HbTheme.studioDimensions.messageCornerRadius
    val zero = HbTheme.spacing.none
    val shape = RoundedCornerShape(
        topStart = if (hasTop) radius else zero,
        topEnd = if (hasTop) radius else zero,
        bottomStart = if (hasBottom) radius else zero,
        bottomEnd = if (hasBottom) radius else zero,
    )
    val outline = HbTheme.studioColors.outline
    val stroke = HbTheme.dimensions.borderWidth
    return background(background, shape).drawWithCache {
        val width = stroke.toPx()
        val corner = radius.toPx()
        val top = if (hasTop) width / 2 else -corner
        val bottom = if (hasBottom) size.height - width / 2 else size.height + corner
        onDrawWithContent {
            drawContent()
            clipRect {
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(width / 2, top),
                    size = Size((size.width - width).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(corner),
                    style = Stroke(width),
                )
            }
        }
    }
}

@Composable
internal fun HbUnifiedMessageHeader(message: HbChatMessage, modifier: Modifier = Modifier) {
    HbRow(modifier.fillMaxWidth().testTag("message-header:${message.id}"), gap = HbTheme.spacing.l) {
        HbStudioMark(Modifier.size(HbTheme.studioDimensions.headerAvatarSize))
        HbText(message.author, style = HbTheme.typography.label.copy(fontWeight = FontWeight.SemiBold))
        message.label?.let { HbText(it, style = HbTheme.typography.metadata, color = HbTheme.colors.textSecondary) }
    }
}

/** Copy is a real action; no elapsed time or completion badge is fabricated from absent engine metadata. */
@Composable
internal fun HbUnifiedMessageFooter(
    message: HbChatMessage,
    streamingLabel: String,
    labels: HbToolLabels,
    modifier: Modifier = Modifier,
) {
    val copyText = remember(message.text, message.parts) {
        if (message.parts.isEmpty()) {
            message.text
        } else {
            message.parts.filterIsInstance<HbMessagePart.Text>().joinToString("\n\n") { it.text }
        }
    }
    HbRow(modifier.fillMaxWidth().padding(top = HbTheme.spacing.l).testTag("message-footer:${message.id}")) {
        MessageCopyButton(copyText, labels)
        Spacer(Modifier.weight(1f))
        if (message.status == HbMessageStatus.Streaming && streamingLabel.isNotBlank()) {
            HbText(
                streamingLabel,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun MessageCopyButton(copyText: String, labels: HbToolLabels) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var isCopied by remember(copyText) { mutableStateOf(false) }
    HbIconButton(
        icon = if (isCopied) HbIcons.Check else HbIcons.Copy,
        contentDescription = if (isCopied) labels.messageCopied else labels.copyMessage,
        enabled = copyText.isNotBlank(),
        onClick = {
            messageLog.i { "copy answer requested length=${copyText.length}" }
            scope.launch { isCopied = copyMessage(clipboard, copyText) }
        },
    )
}

private suspend fun copyMessage(clipboard: Clipboard, text: String): Boolean = try {
    clipboard.setClipEntry(hbPlainTextClipEntry(text))
    true
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    messageLog.e(error) { "copy answer failed" }
    false
}

@Composable
internal fun UnifiedToolPayload(
    body: HbTranscriptBody.ToolPayload,
    isUnified: Boolean,
    labels: HbToolLabels,
    onLinkClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val container = if (isUnified) modifier.unifiedToolPayloadSurface(body.isLast) else modifier
    Box(container) {
        val console = body.row.console
        if (isUnified && console != null) {
            HbScrollableCode(
                text = console.displayText,
                spans = persistentListOf(),
                foreground = HbTheme.colors.textPrimary,
                modifier = Modifier.fillMaxWidth().background(HbTheme.studioColors.console)
                    .padding(HbTheme.studioDimensions.toolPadding),
            )
        } else if (!isUnified || body.row.section == null) {
            HbToolPayloadRow(body.row, labels = labels, onLinkClick = onLinkClick, isSelectionContainerRequired = false)
        }
    }
}

@Composable
@ReadOnlyComposable
private fun Modifier.unifiedToolPayloadSurface(isLast: Boolean): Modifier {
    val radius = HbTheme.studioDimensions.toolPadding
    val zero = HbTheme.spacing.none
    val bottomRadius = if (isLast) radius else zero
    return fillMaxWidth().background(
        HbTheme.studioColors.tool,
        RoundedCornerShape(zero, zero, bottomRadius, bottomRadius),
    ).padding(start = radius, end = radius, bottom = bottomRadius)
}
