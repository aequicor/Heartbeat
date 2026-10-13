package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.ImmutableList

/** The popup uses available window space, so a long library never pushes search and dismissal off screen. */
@Composable
internal fun ComposerMultiSelectPopup(
    actions: ImmutableList<HbComposerAction>,
    labels: HbMultiSelectLabels,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
    onClear: () -> Unit,
) {
    val dimensions = HbTheme.dimensions
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val insets = WindowInsets.safeDrawing.union(WindowInsets.ime)
    val safeBounds = IntRect(
        insets.getLeft(density, androidx.compose.ui.platform.LocalLayoutDirection.current),
        insets.getTop(density),
        window.width - insets.getRight(density, androidx.compose.ui.platform.LocalLayoutDirection.current),
        window.height - insets.getBottom(density),
    )
    val windowWidth = with(density) { safeBounds.width.toDp() }
    val windowHeight = with(density) { safeBounds.height.toDp() }
    val isSheet = windowWidth < dimensions.compactBreakpoint
    val gutter = dimensions.composerMenuGutter
    val anchor = LocalComposerAnchor.current?.value
    // Try above the composer, but allow the whole safe rectangle when that side has too little room.
    val availableHeight = (windowHeight - gutter * 2).coerceAtLeast(dimensions.touchTarget)
    val maxHeight = if (isSheet) availableHeight else minOf(dimensions.composerMenuMaxHeight, availableHeight)
    val width = (windowWidth - gutter * 2).coerceAtLeast(dimensions.touchTarget).let {
        if (isSheet) it else minOf(dimensions.composerMenuMaxWidth, it)
    }
    val position = with(density) {
        remember(isSheet, anchor, safeBounds, gutter, dimensions.composerMenuOffset, this) {
            SelectionPopupPosition(
                safeBounds,
                isSheet,
                anchor,
                gutter.roundToPx(),
                dimensions.composerMenuOffset.roundToPx(),
            )
        }
    }
    HbMenuPopup(position, onDismiss) {
        Box(Modifier.padding(gutter)) {
            ComposerMultiSelectBody(
                actions,
                labels,
                onDismiss,
                onAction,
                onClear,
                isSheet,
                Modifier.width(width).heightIn(max = maxHeight.coerceAtLeast(dimensions.touchTarget)),
            )
        }
    }
}

@Composable
private fun ComposerMultiSelectBody(
    actions: ImmutableList<HbComposerAction>,
    labels: HbMultiSelectLabels,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
    onClear: () -> Unit,
    isSheet: Boolean,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val visible = actions.filter { it.matchesSelectionQuery(query) }
    val focus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(isSheet) {
        withFrameNanos { }
        if (isSheet) focus.requestFocus() else searchFocus.requestFocus()
    }
    HbColumn(
        modifier.hbPopupSurface(HbTheme.colors.surface, HbTheme.shapes.large)
            .semantics { paneTitle = labels.accessibleLabel }
            .testTag(if (isSheet) "multi-select-sheet" else "multi-select-popup")
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .focusRequester(focus).focusable()
            .padding(HbTheme.spacing.m),
        gap = HbTheme.spacing.s,
    ) {
        HbSearchField(
            query,
            { query = it },
            labels.search,
            labels.clearSearch,
            Modifier.fillMaxWidth().focusRequester(searchFocus),
        )
        HbColumn(
            Modifier.weight(1f, fill = false).fillMaxWidth().hbVerticalScroll(rememberScrollState()),
            gap = HbTheme.spacing.xs,
        ) {
            if (visible.isEmpty()) {
                HbText(labels.empty, Modifier.padding(HbTheme.spacing.m), color = HbTheme.colors.textSecondary)
            }
            visible.forEach { action ->
                androidx.compose.runtime.key(action.id) {
                    MultiSelectRow(action, { onAction(action.id) })
                }
            }
        }
        HbDivider()
        HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
            HbText(labels.selected, Modifier.weight(1f), style = HbTheme.typography.caption)
            HbButton(
                labels.clear,
                {
                    // Clearing disables this button; move focus before it leaves keyboard navigation.
                    focus.requestFocus()
                    onClear()
                },
                style = HbButtonStyle.Ghost,
                enabled = actions.any { it.isSelected && it.isEnabled },
            )
        }
        if (isSheet) HbButton(labels.done, onDismiss, Modifier.fillMaxWidth(), style = HbButtonStyle.Secondary)
    }
}

@Composable
private fun MultiSelectRow(action: HbComposerAction, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    val colors = HbTheme.colors
    val shape = HbTheme.shapes.small
    val background = when {
        !action.isEnabled -> Color.Transparent
        isPressed -> colors.pressedOverlay
        action.isSelected -> HbTheme.surfaces.composerPillAccent
        isHovered && action.isEnabled -> colors.interactionHoverOverlay
        else -> Color.Transparent
    }
    HbRow(
        Modifier.fillMaxWidth().heightIn(min = HbTheme.dimensions.touchTarget)
            .hbFocusOutline(isFocused, shape)
            .background(background, shape)
            .toggleable(
                action.isSelected,
                interactions,
                indication = null,
                enabled = action.isEnabled,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            )
            .padding(HbTheme.spacing.m),
        gap = HbTheme.spacing.m,
    ) {
        val checkboxShape = RoundedCornerShape(HbTheme.spacing.xxs)
        Box(
            Modifier.size(HbTheme.dimensions.iconSize)
                .background(if (action.isSelected) colors.brand else Color.Transparent, checkboxShape)
                .border(HbTheme.dimensions.borderWidth, colors.textSecondary, checkboxShape),
            contentAlignment = Alignment.Center,
        ) {
            if (action.isSelected) HbIcon(HbIcons.Check, null, tint = colors.onBrand)
        }
        HbColumn(Modifier.weight(1f), gap = HbTheme.spacing.xxs) {
            HbText(
                action.label,
                style = HbTheme.typography.label,
                maxLines = 1,
                color = if (action.isEnabled) colors.textPrimary else colors.textSecondary,
            )
            action.supportingText?.let {
                HbText(it, style = HbTheme.typography.caption, color = colors.textSecondary)
            }
        }
    }
}

/** Both layouts remain inside safe drawing/IME bounds, even when there is no space above the anchor. */
internal class SelectionPopupPosition(
    private val safeBounds: IntRect,
    private val isSheet: Boolean,
    private val composer: IntRect?,
    private val gutter: Int,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = when {
            isSheet -> safeBounds.left + (safeBounds.width - popupContentSize.width) / 2
            layoutDirection == LayoutDirection.Ltr -> anchorBounds.left - gutter
            else -> anchorBounds.right - popupContentSize.width + gutter
        }
        val y = if (isSheet) {
            safeBounds.bottom - popupContentSize.height
        } else {
            (composer ?: anchorBounds).top - gap - popupContentSize.height + gutter
        }
        return IntOffset(
            x.coerceIn(safeBounds.left, (safeBounds.right - popupContentSize.width).coerceAtLeast(safeBounds.left)),
            y.coerceIn(safeBounds.top, (safeBounds.bottom - popupContentSize.height).coerceAtLeast(safeBounds.top)),
        )
    }
}

private fun HbComposerAction.matchesSelectionQuery(query: String): Boolean = query.isBlank() ||
    label.contains(query.trim(), ignoreCase = true) || supportingText?.contains(query.trim(), ignoreCase = true) == true
