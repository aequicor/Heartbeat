package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbDismissRequestProvider
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
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
 * A non-modal summary at the top-end, beside the conversation when its controls fit and over it otherwise.
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
    onHeightChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (slot != null && isOpen) {
        val gap = HbTheme.spacing.l
        val density = LocalDensity.current
        val width = with(density) { paneSize.width.toDp() }
        val height = with(density) { paneSize.height.toDp() }
        val cardWidth = minOf(
            HbTheme.dimensions.inspectorPanelWidth,
            (width - gap * 2).coerceAtLeast(HbTheme.spacing.none),
        )
        val bottom = if (isBeside) height else composerTop
        Box(
            modifier.padding(top = topInset + gap, end = gap)
                .width(cardWidth)
                .heightIn(max = (bottom - topInset - gap * 2).coerceAtLeast(HbTheme.spacing.none))
                .onSizeChanged { onHeightChange(it.height) }
                .hbSurface(HbTheme.colors.surface, HbTheme.shapes.large)
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

/** The chat first shifts into its leading gutter, then narrows only while its whole context toolbar fits. */
internal data class ContextPanelPlacement(val isBesideAvailable: Boolean, val endInset: Dp) {
    @Composable
    @ReadOnlyComposable
    fun insetFor(slot: StudioEmbed?, isOpen: Boolean, isBeside: Boolean): Dp =
        if (slot != null && isOpen && isBeside) endInset else HbTheme.spacing.none
}

/** Native Start apps must not overlap the card; only its measured top band is reserved, never composer width. */
@Composable
@ReadOnlyComposable
internal fun startAppContextInset(hasStartApp: Boolean, isOpen: Boolean, contextHeight: Int): Dp =
    if (hasStartApp && isOpen) {
        with(LocalDensity.current) { contextHeight.toDp() } + HbTheme.spacing.l * 2
    } else {
        HbTheme.spacing.none
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
