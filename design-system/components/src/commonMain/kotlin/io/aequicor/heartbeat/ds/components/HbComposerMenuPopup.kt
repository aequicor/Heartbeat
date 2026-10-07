package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.ImmutableList

@Composable
internal fun ComposerMenuPopup(
    actions: ImmutableList<HbComposerAction>,
    label: String,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
    headerLabel: String? = null,
) {
    val anchor = LocalComposerAnchor.current?.value
    val density = LocalDensity.current
    val dimensions = HbTheme.dimensions
    val gutter = maxOf(dimensions.composerMenuGutter, dimensions.popupShadowRadius + dimensions.popupShadowOffset)
    val window = LocalWindowInfo.current.containerSize
    val availableWidth = with(density) { window.width.toDp() } - gutter * 2
    val availableHeight = with(
        density,
    ) { (anchor?.top ?: window.height).toDp() } - gutter - dimensions.composerMenuOffset
    val menuWidth = minOf(dimensions.composerMenuMaxWidth, availableWidth.coerceAtLeast(dimensions.touchTarget))
    val menuHeight = minOf(dimensions.composerMenuMaxHeight, availableHeight.coerceAtLeast(dimensions.touchTarget))
    val position = with(density) {
        remember(anchor, gutter, dimensions.composerMenuOffset, this) {
            ComposerPopupPosition(anchor, gutter.roundToPx(), dimensions.composerMenuOffset.roundToPx())
        }
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(modifier = modifier.padding(gutter)) {
            ComposerMenuSheet(actions, label, onDismiss, onAction, menuWidth, menuHeight, headerLabel = headerLabel)
        }
    }
}

@Composable
private fun ComposerMenuSheet(
    actions: ImmutableList<HbComposerAction>,
    label: String,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
    menuWidth: Dp,
    menuHeight: Dp,
    modifier: Modifier = Modifier,
    headerLabel: String? = null,
) {
    val focusRequests = remember(actions) { List(actions.size) { FocusRequester() } }
    val enabledIndices = remember(actions) { actions.indices.filter { actions[it].isEnabled } }
    var focusedIndex by remember(actions) { mutableIntStateOf(enabledIndices.firstOrNull() ?: -1) }
    val emptyFocus = remember { FocusRequester() }
    SideEffect(actions) {
        if (focusedIndex >= 0) focusRequests[focusedIndex].requestFocus() else emptyFocus.requestFocus()
    }
    HbColumn(
        modifier = modifier
            .width(menuWidth)
            .heightIn(max = menuHeight)
            .onPreviewKeyEvent { event ->
                handleMenuKey(event, focusedIndex, enabledIndices, onDismiss) { nextIndex ->
                    focusedIndex = nextIndex
                    focusRequests[nextIndex].requestFocus()
                }
            }
            .semantics { paneTitle = label }
            // Popups may use a separate native window, so underlying text cannot be blurred reliably.
            .background(HbTheme.colors.surfaceElevated, HbTheme.shapes.large)
            .hbPopupSurface(HbTheme.colors.surface, HbTheme.shapes.medium)
            .focusRequester(emptyFocus)
            .focusable(enabledIndices.isEmpty())
            .hbVerticalScroll(rememberScrollState())
            .padding(HbTheme.spacing.s),
        gap = HbTheme.spacing.xs,
    ) {
        if (headerLabel != null) ComposerSectionLabel(headerLabel)
        actions.forEachIndexed { index, action ->
            if (action.sectionLabel != null) ComposerSectionLabel(action.sectionLabel)
            ComposerMenuItem(
                action = action,
                onClick = { onAction(action.id) },
                onFocus = { focusedIndex = index },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequests[index]),
            )
        }
    }
}

@Composable
internal fun ComposerSectionLabel(label: String, modifier: Modifier = Modifier) {
    HbText(
        text = label,
        modifier = modifier.padding(
            horizontal = HbTheme.spacing.m,
            vertical = HbTheme.spacing.s,
        ).semantics { heading() },
        style = HbTheme.typography.caption,
        color = HbTheme.colors.textSecondary,
    )
}

private fun handleMenuKey(
    event: KeyEvent,
    focusedIndex: Int,
    enabledIndices: List<Int>,
    onDismiss: () -> Unit,
    onFocus: (Int) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.Escape) {
        onDismiss()
        return true
    }
    val nextIndex = composerMenuFocusIndex(
        event.key,
        event.isShiftPressed,
        focusedIndex,
        enabledIndices,
    ) ?: return false
    if (nextIndex >= 0) onFocus(nextIndex)
    return true
}

internal fun composerMenuFocusIndex(key: Key, isShiftPressed: Boolean, current: Int, enabled: List<Int>): Int? {
    val position = enabled.indexOf(current).coerceAtLeast(0)
    return when (key) {
        Key.MoveHome -> enabled.firstOrNull() ?: -1

        Key.MoveEnd -> enabled.lastOrNull() ?: -1

        Key.DirectionDown -> enabled.getOrNull((position + 1) % enabled.size.coerceAtLeast(1)) ?: -1

        Key.DirectionUp -> enabled.getOrNull((position + enabled.size - 1) % enabled.size.coerceAtLeast(1)) ?: -1

        Key.Tab -> {
            val step = if (isShiftPressed) enabled.size - 1 else 1
            enabled.getOrNull((position + step) % enabled.size.coerceAtLeast(1)) ?: -1
        }

        else -> null
    }
}
