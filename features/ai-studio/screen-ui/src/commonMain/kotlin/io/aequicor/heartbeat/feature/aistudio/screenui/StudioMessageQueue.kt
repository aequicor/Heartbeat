package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbAttachmentThumbnail
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbPopover
import io.aequicor.heartbeat.ds.components.HbPressableRow
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTooltip
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.queue_close
import io.aequicor.heartbeat.feature.aistudio.impl.resources.queue_correct
import io.aequicor.heartbeat.feature.aistudio.impl.resources.queue_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.queue_file
import io.aequicor.heartbeat.feature.aistudio.impl.resources.queue_more
import io.aequicor.heartbeat.feature.aistudio.impl.resources.queue_remove
import io.aequicor.heartbeat.feature.aistudio.impl.resources.queue_resume
import io.aequicor.heartbeat.feature.aistudio.impl.resources.queue_success
import io.aequicor.heartbeat.feature.aistudio.impl.resources.queue_uncertain
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.QueueActionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.QueuedMessageUi
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/** A bounded header attached to the editor; the bound is visual and never truncates the durable FIFO. */
@Composable
internal fun StudioMessageQueue(content: PaneContent, onIntent: (StudioIntent) -> Unit, modifier: Modifier = Modifier) {
    val messages = content.queue.messages
    if (messages.isEmpty()) return
    HbBoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.l)) {
        val isNarrow = maxWidth < HbTheme.dimensions.composerToolbarBreakpoint
        val overlap = HbTheme.spacing.xs
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = HbTheme.shapes.large.topStart,
            topEnd = HbTheme.shapes.large.topEnd,
            bottomStart = androidx.compose.foundation.shape.CornerSize(0),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0),
        )
        HbPanel(
            Modifier.layout { measurable, constraints ->
                val header = measurable.measure(constraints)
                layout(
                    header.width,
                    (header.height - overlap.roundToPx()).coerceAtLeast(0),
                ) { header.placeRelative(0, 0) }
            },
            background = HbTheme.surfaces.composer,
            shape = shape,
        ) {
            val visible = messages.take(VISIBLE_QUEUE_ROWS)
            HbColumn(Modifier.padding(bottom = overlap), gap = HbTheme.spacing.none) {
                visible.forEachIndexed { index, message ->
                    QueueRow(message, content, onIntent, isNarrow, hasDivider = index < visible.lastIndex)
                }
            }
        }
    }
}

/** Extra entries remain in an anchored, scrollable list with the same three-row viewport. */
@Composable
internal fun QueueControls(content: PaneContent, onIntent: (StudioIntent) -> Unit) {
    if (content.queue.messages.isEmpty()) return
    HbRow(gap = HbTheme.spacing.xs) {
        if (content.queue.isPaused) {
            HbButton(
                stringResource(Res.string.queue_resume),
                { queueAction(content, onIntent, QueueActionUi.Resume) },
                style = HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        }
        if (content.queue.messages.size > VISIBLE_QUEUE_ROWS) {
            QueueOverflow(content, onIntent)
        }
    }
}

@Composable
private fun QueueOverflow(content: PaneContent, onIntent: (StudioIntent) -> Unit) {
    var isExpanded by remember(content.pane.sessionId) { mutableStateOf(false) }
    val trigger = remember { FocusRequester() }
    var hasOpened by remember { mutableStateOf(false) }
    SideEffect(isExpanded) {
        if (!isExpanded && hasOpened) trigger.requestFocus()
        if (isExpanded) hasOpened = true
    }
    val hiddenCount = content.queue.messages.size - VISIBLE_QUEUE_ROWS
    val label = stringResource(Res.string.queue_more, hiddenCount)
    Box {
        HbButton(
            label,
            { isExpanded = !isExpanded },
            Modifier.focusRequester(trigger).testTag("queue-more"),
            style = HbButtonStyle.Ghost,
            size = HbButtonSize.Small,
        )
        HbPopover(isExpanded, { isExpanded = false }, label) {
            HbBoxWithConstraints(
                Modifier.widthIn(max = HbTheme.dimensions.composerMaxWidth - HbTheme.spacing.l * 2),
            ) {
                val isNarrow = maxWidth < HbTheme.dimensions.composerToolbarBreakpoint
                val viewportHeight = HbTheme.dimensions.navigationRowHeight * VISIBLE_QUEUE_ROWS + HbTheme.spacing.xs
                HbLazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = viewportHeight),
                    gap = HbTheme.spacing.none,
                    contentPadding = PaddingValues(bottom = HbTheme.spacing.xs),
                ) {
                    itemsIndexed(content.queue.messages, key = { _, message -> message.id }) { index, message ->
                        QueueRow(
                            message,
                            content,
                            onIntent,
                            isNarrow,
                            hasDivider = index < content.queue.messages.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    message: QueuedMessageUi,
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    isNarrow: Boolean,
    hasDivider: Boolean,
) {
    Box {
        QueueRowContent(message, content, onIntent, isNarrow)
        if (hasDivider) HbDivider(Modifier.align(Alignment.BottomCenter).padding(horizontal = HbTheme.spacing.m))
    }
}

@Composable
private fun QueueRowContent(
    message: QueuedMessageUi,
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    isNarrow: Boolean,
) {
    val file = message.attachments.firstOrNull()
    val label = message.text.ifBlank { file?.name?.ifBlank { null } ?: stringResource(Res.string.queue_file) }
    val correct = stringResource(Res.string.queue_correct)
    val isPrimary = content.subSession == io.aequicor.heartbeat.feature.aistudio.screenmodel.PRIMARY_SUB_SESSION
    val isCorrectable = content.host.isTurnSteeringAvailable && isPrimary &&
        content.session?.isRunning == true && message.isPending &&
        content.queue.messages.all { it.isPending }
    HbRow(
        Modifier.fillMaxWidth().height(HbTheme.dimensions.navigationRowHeight)
            .padding(horizontal = HbTheme.spacing.xs).testTag("queued-${message.id}"),
        gap = HbTheme.spacing.xs,
    ) {
        if (file == null) {
            HbTooltip(label, Modifier.weight(1f)) {
                HbText(label, Modifier.fillMaxWidth(), maxLines = 1, style = HbTheme.typography.caption)
            }
        } else {
            QueueAttachment(file, label, content, onIntent, Modifier.weight(1f))
        }
        if (message.isUncertain) {
            HbTooltip(stringResource(Res.string.queue_uncertain)) {
                HbText("?", color = HbTheme.colors.errorForeground, style = HbTheme.typography.caption)
            }
        }
        if (isNarrow) {
            HbIconButton(
                HbIcons.Reply,
                correct,
                { queueAction(content, onIntent, QueueActionUi.Correct, message.id) },
                modifier = Modifier.testTag("queued-correct-${message.id}"),
                enabled = isCorrectable,
                size = HbTheme.dimensions.navigationRowHeight,
            )
        } else {
            HbButton(
                correct,
                { queueAction(content, onIntent, QueueActionUi.Correct, message.id) },
                modifier = Modifier.testTag("queued-correct-${message.id}"),
                style = HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
                enabled = isCorrectable,
            )
        }
        HbIconButton(
            HbIcons.Trash,
            stringResource(Res.string.queue_remove),
            { queueAction(content, onIntent, QueueActionUi.Remove, message.id) },
            enabled = message.isPending || message.isUncertain,
            size = HbTheme.dimensions.navigationRowHeight,
        )
    }
}

/** The existing attachment viewer opens from a full row target; the image itself stays compact. */
@Composable
private fun QueueAttachment(
    file: AttachmentUi,
    label: String,
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val callback by rememberUpdatedState(onIntent)
    DisposableEffect(file.id, file.mediaType) {
        callback(StudioIntent.AttachmentPreviewVisible(file.id, file.mediaType, true))
        onDispose { callback(StudioIntent.AttachmentPreviewVisible(file.id, file.mediaType, false)) }
    }
    val name = file.name.ifBlank { stringResource(Res.string.queue_file) }
    val preview = content.attachmentPreviews[file.id]
    HbPressableRow(
        onClick = { onIntent(StudioIntent.OpenAttachment(file.id)) }
            .takeIf { content.host.isAttachmentOpeningAvailable },
        modifier = modifier.testTag("queued-attachment-${file.id}"),
        gap = HbTheme.spacing.xs,
    ) {
        HbTooltip(name) {
            HbAttachmentThumbnail(
                preview?.imageBytes,
                null,
                file.mediaType.startsWith("image/"),
                Modifier.size(HbTheme.spacing.xxl),
                contentDescription = name,
                isUnavailable = preview?.isUnavailable == true,
            )
        }
        HbTooltip(label, Modifier.weight(1f)) {
            HbText(label, Modifier.fillMaxWidth(), maxLines = 1, style = HbTheme.typography.caption)
        }
    }
}

/** Passive feedback overlays the feed. Hover and keyboard focus pause auto-dismissal; no focus is requested. */
@Composable
internal fun QueueSnackbar(content: PaneContent, onIntent: (StudioIntent) -> Unit, modifier: Modifier = Modifier) {
    val notice = content.queueNotice ?: return
    val interactions = remember { MutableInteractionSource() }
    val isHovered by interactions.collectIsHoveredAsState()
    var isFocused by remember(notice.id) { mutableStateOf(false) }
    val callback by rememberUpdatedState(onIntent)
    LaunchedEffect(notice.id, isHovered, isFocused) {
        if (!isHovered && !isFocused) {
            delay(6.seconds)
            queueAction(content, callback, QueueActionUi.DismissNotice, notice.id)
        }
    }
    val text = stringResource(
        when {
            notice.isSuccess -> Res.string.queue_success
            notice.isUncertain -> Res.string.queue_uncertain
            else -> Res.string.queue_failed
        },
    )
    HbPanel(
        modifier.hoverable(interactions).onFocusChanged { isFocused = it.hasFocus }
            .semantics { liveRegion = LiveRegionMode.Polite }.testTag("queue-snackbar"),
    ) {
        HbRow(Modifier.padding(start = HbTheme.spacing.m), gap = HbTheme.spacing.s) {
            HbText(
                text,
                Modifier.weight(1f),
                style = HbTheme.typography.caption,
                color = if (notice.isSuccess) HbTheme.colors.textPrimary else HbTheme.colors.errorForeground,
            )
            HbIconButton(
                HbIcons.Close,
                stringResource(Res.string.queue_close),
                { queueAction(content, onIntent, QueueActionUi.DismissNotice, notice.id) },
            )
        }
    }
}

private fun queueAction(
    content: PaneContent,
    onIntent: (StudioIntent) -> Unit,
    action: QueueActionUi,
    id: String = "",
) {
    content.pane.sessionId?.let { onIntent(StudioIntent.ManageQueue(it, action, id)) }
}

private const val VISIBLE_QUEUE_ROWS = 3
