package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.hbScrollbars
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.math.roundToInt

/** Foundation viewer shared by all kits, hosted in the existing modal input barrier across the entire window. */
@Composable
internal fun DiagramViewer(
    block: HbMarkdownBlock,
    image: HbDiagramResult.Image,
    labels: HbDiagramLabels,
    foreground: Color,
    onLinkClick: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var isSourceShown by remember { mutableStateOf(false) }
    var isActivated by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val state = remember(image, density) {
        HbDiagramViewportState(with(density) { Size(image.size.width.toPx(), image.size.height.toPx()) })
    }
    val onAction = currentHbDiagrams?.onAction
    val activateAction: (HbDiagramAction) -> Unit = { action ->
        if (!isActivated) {
            isActivated = true
            onAction?.invoke(action)
            onDismiss()
        }
    }
    DiagramModal(onDismiss) {
        HbColumn(
            Modifier.fillMaxSize().background(HbTheme.colors.surface)
                .testTag("diagram-window")
                .semantics { paneTitle = labels.diagram }
                .diagramDismissOnEscape(onDismiss).padding(HbTheme.spacing.m),
            gap = HbTheme.spacing.m,
        ) {
            DiagramToolbar(block, state, labels, isSourceShown, onDismiss) { isSourceShown = !isSourceShown }
            Box(Modifier.weight(1f).fillMaxSize()) {
                if (isSourceShown) {
                    DiagramSource(block, foreground)
                } else {
                    DiagramImageViewport(
                        image,
                        state,
                        labels,
                        onLinkClick,
                        !isActivated && onAction != null,
                        onAction = activateAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagramImageViewport(
    image: HbDiagramResult.Image,
    state: HbDiagramViewportState,
    labels: HbDiagramLabels,
    onLinkClick: ((String) -> Unit)?,
    isActionEnabled: Boolean,
    onAction: (HbDiagramAction) -> Unit,
) {
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.requestFocus()
    }
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val step = with(density) { HbTheme.dimensions.keyboardScrollStep.toPx() }
    Box(
        Modifier.fillMaxSize().testTag(DIAGRAM_VIEWER_TAG).clipToBounds()
            .background(HbTheme.colors.surfaceElevated)
            .onSizeChanged { state.resize(Size(it.width.toFloat(), it.height.toFloat())) }
            .onKeyEvent { it.type == KeyEventType.KeyDown && state.onKey(it.key, step) }
            .hbFocusOutline(isFocused, HbTheme.shapes.small)
            .focusRequester(focus).focusable(interactionSource = interactions)
            .semantics {
                horizontalScrollAxisRange = ScrollAxisRange({ state.horizontal.position }, { 1f })
                verticalScrollAxisRange = ScrollAxisRange({ state.vertical.position }, { 1f })
            }
            .hbScrollbars(state.vertical, Orientation.Vertical)
            .hbScrollbars(state.horizontal, Orientation.Horizontal)
            .diagramWheelZoom(state)
            .diagramGestures(state) { focus.requestFocus() },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawImage(
                image.bitmap,
                dstOffset = IntOffset(state.offset.x.roundToInt(), state.offset.y.roundToInt()),
                dstSize = IntSize(
                    state.scaledSize.width.roundToInt().coerceAtLeast(1),
                    state.scaledSize.height.roundToInt().coerceAtLeast(1),
                ),
                filterQuality = FilterQuality.Medium,
            )
        }
        DiagramLinks(image, state, labels, onLinkClick, isActionEnabled, onAction)
    }
}

@Composable
private fun DiagramLinkTarget(
    description: String,
    tooltip: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val isHovered by interactions.collectIsHoveredAsState()
    val isFocused by interactions.collectIsFocusedAsState()
    val highlight = if (isHovered || isFocused) {
        Modifier.background(
            HbTheme.colors.interactionHoverOverlay,
        )
    } else {
        Modifier
    }
    HbTooltip(if (tooltip.isBlank() || tooltip == description) description else "$tooltip\n$description", modifier) {
        Box(
            Modifier.fillMaxSize()
                .then(highlight)
                .hbFocusOutline(isFocused, HbTheme.shapes.small)
                .semantics { contentDescription = description }
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        )
    }
}

private fun HbDiagramViewportState.onKey(key: Key, step: Float): Boolean {
    when (key) {
        Key.DirectionLeft -> pan(Offset(step, 0f))

        Key.DirectionRight -> pan(Offset(-step, 0f))

        Key.DirectionUp -> pan(Offset(0f, step))

        Key.DirectionDown -> pan(Offset(0f, -step))

        Key.PageUp -> pan(Offset(0f, viewport.height))

        Key.PageDown -> pan(Offset(0f, -viewport.height))

        Key.MoveHome -> {
            horizontal.scrollTo(0f)
            vertical.scrollTo(0f)
        }

        Key.MoveEnd -> {
            horizontal.scrollTo(1f)
            vertical.scrollTo(1f)
        }

        Key.Equals, Key.Plus -> zoom(scale * ZOOM_STEP)

        Key.Minus -> zoom(scale / ZOOM_STEP)

        else -> return false
    }
    return true
}

private const val ZOOM_STEP = 1.2f

@Composable
private fun DiagramLinks(
    image: HbDiagramResult.Image,
    state: HbDiagramViewportState,
    labels: HbDiagramLabels,
    onLinkClick: ((String) -> Unit)?,
    isActionEnabled: Boolean,
    onAction: (HbDiagramAction) -> Unit,
) {
    image.links.withIndex().reversed().forEach { (index, link) ->
        DiagramLink(link, index, state, labels, onLinkClick, isActionEnabled, onAction)
    }
}

@Composable
private fun DiagramLink(
    link: HbDiagramLink,
    index: Int,
    state: HbDiagramViewportState,
    labels: HbDiagramLabels,
    onLinkClick: ((String) -> Unit)?,
    isActionEnabled: Boolean,
    onAction: (HbDiagramAction) -> Unit,
) {
    val density = LocalDensity.current
    val action = remember(link.destination) { parseDiagramAction(link.destination) }
    val isExternal = link.destination.substringBefore(':').lowercase() in setOf("http", "https", "mailto")
    val isEnabled = if (action != null) isActionEnabled else isExternal && onLinkClick != null
    val description = when (action?.kind) {
        HbDiagramActionKind.Insert -> "${labels.insertText}: ${action.text}"
        HbDiagramActionKind.Send -> "${labels.sendText}: ${action.text}"
        null -> link.destination
    }
    val title = if (action != null && !isEnabled) "${labels.unavailableAction}: $description" else description
    val bounds = link.bounds
    val factor = density.density * state.scale
    val left = (bounds.left * factor + state.offset.x).coerceAtLeast(0f)
    val top = (bounds.top * factor + state.offset.y).coerceAtLeast(0f)
    val right = (bounds.right * factor + state.offset.x).coerceAtMost(state.viewport.width)
    val bottom = (bounds.bottom * factor + state.offset.y).coerceAtMost(state.viewport.height)
    if (right > left && bottom > top) {
        DiagramLinkTarget(
            title,
            link.tooltip,
            isEnabled,
            modifier = Modifier.absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
                .testTag("diagram-link-$index"),
            onClick = { if (action != null) onAction(action) else onLinkClick?.invoke(link.destination) },
        )
    }
}

@Composable
private fun DiagramToolbar(
    block: HbMarkdownBlock,
    state: HbDiagramViewportState,
    labels: HbDiagramLabels,
    isSourceShown: Boolean,
    onDismiss: () -> Unit,
    onToggleSource: () -> Unit,
) {
    HbFlowRow(gap = HbTheme.spacing.s) {
        HbText(labels.diagram)
        HbTooltip(labels.zoomOut) {
            HbButton(
                "−",
                { state.zoom(state.scale / ZOOM_STEP) },
                enabled = !isSourceShown,
                modifier = Modifier.semantics { contentDescription = labels.zoomOut },
                style = HbButtonStyle.Ghost,
            )
        }
        HbText("${(state.scale * 100).roundToInt()}%", Modifier.testTag("diagram-scale"))
        HbTooltip(labels.zoomIn) {
            HbButton(
                "+",
                { state.zoom(state.scale * ZOOM_STEP) },
                enabled = !isSourceShown,
                modifier = Modifier.semantics { contentDescription = labels.zoomIn },
                style = HbButtonStyle.Ghost,
            )
        }
        HbButton(labels.fit, state::fit, enabled = !isSourceShown, style = HbButtonStyle.Ghost)
        HbButton(labels.actualSize, { state.zoom(1f) }, enabled = !isSourceShown, style = HbButtonStyle.Ghost)
        HbButton(
            if (isSourceShown) labels.showDiagram else labels.showSource,
            onToggleSource,
            style = HbButtonStyle.Ghost,
        )
        HbCopyButton(block.content.text, labels.copySource, labels.sourceCopied, labels.copyFailed)
        HbButton(labels.close, onDismiss, style = HbButtonStyle.Secondary)
    }
}

@Composable
private fun DiagramSource(block: HbMarkdownBlock, foreground: Color) {
    DiagramSourceViewport {
        SelectionContainer {
            HbCodeText(
                block.content.text,
                remember(block.content.text) { highlightHbCode(block.content.text, block.language) },
                foreground = foreground,
            )
        }
    }
}

private fun Modifier.diagramDismissOnEscape(onDismiss: () -> Unit): Modifier = onPreviewKeyEvent {
    val isDismissKey = it.type == KeyEventType.KeyDown && it.key == Key.Escape
    if (isDismissKey) onDismiss()
    isDismissKey
}

/** Modal controls must not register selectable text in the transcript's separate layout hierarchy. */
@Composable
private fun DiagramModal(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DisableSelection { HbPlatformDialog(onDismiss, content) }
}
