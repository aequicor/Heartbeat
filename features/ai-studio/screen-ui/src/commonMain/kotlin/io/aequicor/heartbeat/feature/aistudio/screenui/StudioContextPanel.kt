package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbDismissRequestProvider
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.hbEdgeSurface
import io.aequicor.heartbeat.ds.components.hbSurface
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_context_hide
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_context_panel
import io.aequicor.heartbeat.feature.aistudio.impl.resources.pane_context_show
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** A persistent header action leaves the input usable while the non-modal card is open. */
@Composable
internal fun PaneContextToggle(
    onClick: (() -> Unit)?,
    isOpen: Boolean,
    isCompact: Boolean,
    paneId: Int,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    if (onClick == null) return
    val trigger = modifier.focusRequester(focus).testTag("pane-open-context-$paneId")
    val label = stringResource(if (isOpen) Res.string.pane_context_hide else Res.string.pane_context_show)
    if (isCompact) {
        HbIconButton(HbIcons.Sidebar, label, onClick, trigger)
    } else {
        HbChip(
            label = stringResource(Res.string.pane_context_panel),
            modifier = trigger,
            icon = HbIcons.Sidebar,
            onClick = onClick,
            accessibleLabel = label,
            trailingIcon = if (isOpen) HbIcons.ChevronUp else HbIcons.ChevronDown,
        )
    }
}

/**
 * A non-modal summary at the top-end: a card beside the conversation when its controls fit, otherwise a panel
 * attached below the header and to the pane's end edge that overlays the conversation without moving or narrowing it.
 * The card intercepts only its own bounds, never captures focus on appearance, and leaves scrolling to its renderer.
 */
@Composable
internal fun PaneContextPanel(
    slot: StudioEmbed?,
    isOpen: Boolean,
    isBeside: Boolean,
    onDismiss: () -> Unit,
    paneId: Int,
    topInset: Dp,
    composerTop: Dp,
    paneSize: IntSize,
    modifier: Modifier = Modifier,
) {
    if (slot != null && isOpen) {
        Box(
            modifier.contextPanelFrame(isBeside, topInset, composerTop, paneSize)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .pointerInput(Unit) { detectTapGestures { } }
                .testTag("pane-context-panel-$paneId"),
        ) {
            HbDismissRequestProvider(onDismiss) { slot.Content(Modifier.fillMaxWidth()) }
        }
    }
}

/** Beside the conversation it is a card; over it, a panel below the header continuing the pane's end edge. */
@Composable
@ReadOnlyComposable
private fun Modifier.contextPanelFrame(isBeside: Boolean, topInset: Dp, composerTop: Dp, paneSize: IntSize): Modifier {
    val gap = HbTheme.spacing.l
    val none = HbTheme.spacing.none
    val density = LocalDensity.current
    val width = with(density) { paneSize.width.toDp() }
    val bottom = if (isBeside) with(density) { paneSize.height.toDp() } else composerTop
    val inset = if (isBeside) gap else none
    val surface = if (isBeside) {
        Modifier.hbSurface(HbTheme.colors.surface, HbTheme.shapes.large)
    } else {
        val square = CornerSize(none)
        Modifier.hbEdgeSurface(
            HbTheme.colors.surface,
            HbTheme.shapes.large.copy(topStart = square, topEnd = square, bottomEnd = square),
        )
    }
    return padding(top = topInset + inset, end = inset)
        .width(minOf(HbTheme.dimensions.inspectorPanelWidth, (width - inset * 2).coerceAtLeast(none)))
        .heightIn(max = (bottom - topInset - inset - gap).coerceAtLeast(none))
        .then(surface)
}

/** The chat first shifts into its leading gutter, then narrows only while its whole context toolbar fits. */
internal data class ContextPanelPlacement(val isBesideAvailable: Boolean, val endInset: Dp) {
    @Composable
    @ReadOnlyComposable
    fun insetFor(slot: StudioEmbed?, isOpen: Boolean, isBeside: Boolean): Dp =
        if (slot != null && isOpen && isBeside) endInset else HbTheme.spacing.none
}

@Composable
@ReadOnlyComposable
internal fun contextPanelPlacement(
    width: Dp,
    contextRowWidth: Dp?,
    isCompact: Boolean,
    isCenteredComposer: Boolean,
): ContextPanelPlacement {
    val spacing = HbTheme.spacing
    val dimensions = HbTheme.dimensions
    val composerPadding = (if (isCompact) spacing.m else spacing.xl) * 2 +
        if (isCenteredComposer) spacing.xl * 2 else spacing.none
    val fullWidth = maxOf(dimensions.messageMaxWidth + spacing.xl * 2, dimensions.composerMaxWidth + composerPadding)
    val minimumWidth = contextRowWidth?.let {
        maxOf(dimensions.composerToolbarBreakpoint, it) + composerPadding
    } ?: fullWidth
    val reserved = dimensions.inspectorPanelWidth + spacing.l * 2
    return ContextPanelPlacement(
        isBesideAvailable = width >= minOf(fullWidth, minimumWidth) + reserved,
        endInset = (fullWidth + reserved * 2 - width).coerceIn(spacing.none, reserved),
    )
}

/** Local presentation of a retained context slot; opening it never changes the chat or its draft. */
internal data class ContextPanelPresentation(
    val isOpen: Boolean,
    val focus: FocusRequester,
    val toggle: (() -> Unit)?,
    val dismiss: () -> Unit,
)

@Composable
internal fun rememberPaneContextPanel(
    content: PaneContent,
    slot: StudioEmbed?,
    isBesideAvailable: Boolean,
): ContextPanelPresentation {
    var isExpandedByUser by remember(content.pane.sessionId, content.pane.projectId, content.harnesses) {
        mutableStateOf<Boolean?>(null)
    }
    val isOpen = isExpandedByUser ?: isBesideAvailable
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val isContextFocusRestorable by rememberUpdatedState(slot != null)
    SideEffect(slot) { if (slot == null) isExpandedByUser = null }
    val dismiss = {
        isExpandedByUser = false
        scope.launch {
            withFrameNanos { }
            if (isContextFocusRestorable) focus.requestFocus()
        }
        Unit
    }
    return ContextPanelPresentation(
        isOpen,
        focus,
        toggle = {
            // Collapsing the card must not leave focus on a removed descendant.
            focus.requestFocus()
            isExpandedByUser = !isOpen
        }.takeIf { slot != null },
        dismiss = dismiss,
    )
}
