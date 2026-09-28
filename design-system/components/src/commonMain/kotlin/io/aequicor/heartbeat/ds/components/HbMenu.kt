package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.ImmutableList

private val log = Log.tag("DS/Menu")

/**
 * A localized menu command. The owner handles [id] through [HbMenu]'s callback.
 * [shortcut] is a caller-formatted hint shown at the end of the row; [isChecked] marks the current choice
 * of a single-selection group; [isGroupStart] draws a divider above the item.
 * Set [isFocusRestoredOnSelect] to false when the command opens an editor or another focused destination.
 */
@Immutable
public data class HbMenuItem(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val shortcut: String? = null,
    val isEnabled: Boolean = true,
    val isChecked: Boolean = false,
    val isGroupStart: Boolean = false,
    val isFocusRestoredOnSelect: Boolean = true,
)

/**
 * Controlled popup menu anchored to its parent layout: place it in the same `Box` as its trigger.
 * Opens below the anchor and flips above when the window lacks space. Arrow keys, Home/End and Tab
 * move between enabled items; Enter/Space activate; Escape and outside clicks dismiss.
 * Every visual style shares this foundation popup; native menus cannot host the shared item semantics.
 */
@Composable
public fun HbMenu(
    items: ImmutableList<HbMenuItem>,
    isExpanded: Boolean,
    onDismiss: () -> Unit,
    onItem: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    if (!isExpanded || items.isEmpty()) return
    require(items.map { it.id }.distinct().size == items.size) { "Menu item ids must be unique" }
    val gap = with(LocalDensity.current) { HbTheme.spacing.xs.roundToPx() }
    val position = remember(gap) { HbMenuPosition(gap) }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = {
            log.i { "menu dismissed" }
            onDismiss()
        },
        properties = PopupProperties(focusable = true),
    ) {
        MenuSheet(
            items = items,
            label = label,
            onDismiss = onDismiss,
            onItem = { id ->
                log.i { "menu item activated" }
                onDismiss()
                onItem(id)
            },
            modifier = modifier,
        )
    }
}

/**
 * Icon trigger with its [HbMenu]. Dismissal returns focus to the trigger; selected commands may opt out
 * through [HbMenuItem.isFocusRestoredOnSelect] so their destination can retain focus.
 * [contentDescription] labels both the trigger and the menu pane.
 */
@Composable
public fun HbMenuButton(
    icon: ImageVector,
    contentDescription: String,
    items: ImmutableList<HbMenuItem>,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onItem: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = HbTheme.dimensions.touchTarget,
) {
    val triggerFocus = remember { FocusRequester() }
    var hasOpened by remember { mutableStateOf(false) }
    var isFocusRestored by remember { mutableStateOf(true) }
    val isOpen = isExpanded && enabled && items.isNotEmpty()
    SideEffect(isOpen) {
        if (!isOpen && hasOpened && isFocusRestored) triggerFocus.requestFocus()
        if (isOpen) isFocusRestored = true
        hasOpened = isOpen
    }
    Box(modifier = modifier) {
        HbIconButton(
            icon = icon,
            contentDescription = contentDescription,
            onClick = { onExpandedChange(!isOpen) },
            modifier = Modifier.focusRequester(triggerFocus),
            enabled = enabled && items.isNotEmpty(),
            isSelected = isOpen,
            size = size,
        )
        HbMenu(
            items = items,
            isExpanded = isOpen,
            onDismiss = { onExpandedChange(false) },
            onItem = { id ->
                isFocusRestored = items.firstOrNull { it.id == id }?.isFocusRestoredOnSelect ?: true
                onItem(id)
            },
            label = contentDescription,
        )
    }
}

@Composable
private fun MenuSheet(
    items: ImmutableList<HbMenuItem>,
    label: String,
    onDismiss: () -> Unit,
    onItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val requests = remember(items) { List(items.size) { FocusRequester() } }
    val enabled = remember(items) { items.indices.filter { items[it].isEnabled } }
    var focused by remember(items) { mutableIntStateOf(enabled.firstOrNull() ?: -1) }
    SideEffect(items) { if (focused >= 0) requests[focused].requestFocus() }
    HbColumn(
        modifier = modifier
            .width(HbTheme.dimensions.composerMenuMinWidth)
            .heightIn(max = HbTheme.dimensions.composerMenuMaxHeight)
            .onPreviewKeyEvent { event ->
                handleMenuSheetKey(event, focused, enabled, onDismiss) { next ->
                    focused = next
                    requests[next].requestFocus()
                }
            }
            .semantics { paneTitle = label }
            // Popups may use a separate native window, so the menu uses an opaque elevated fill.
            .background(HbTheme.colors.surfaceElevated, HbTheme.shapes.large)
            .hbPopupSurface(HbTheme.colors.surface, HbTheme.shapes.medium)
            .hbVerticalScroll(rememberScrollState())
            .padding(HbTheme.spacing.xs),
        gap = HbTheme.spacing.none,
    ) {
        items.forEachIndexed { index, item ->
            key(item.id) {
                if (item.isGroupStart && index > 0) {
                    HbDivider(Modifier.padding(vertical = HbTheme.spacing.xs))
                }
                MenuRow(
                    item = item,
                    onClick = { onItem(item.id) },
                    modifier = Modifier.fillMaxWidth().focusRequester(requests[index])
                        .onFocusChanged { if (it.isFocused) focused = index },
                )
            }
        }
    }
}

@Composable
private fun MenuRow(item: HbMenuItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    val colors = HbTheme.colors
    val background = menuRowBackground(item.isEnabled, isPressed, isHovered || isFocused)
    val foreground = if (item.isEnabled) colors.textPrimary else colors.textSecondary
    HbRow(
        modifier = modifier
            .heightIn(min = HbTheme.dimensions.touchTarget)
            .semantics { if (item.isChecked) selected = true }
            .clickable(interactions, indication = null, enabled = item.isEnabled, role = Role.Button, onClick = onClick)
            .background(background, HbTheme.shapes.small)
            .padding(horizontal = HbTheme.spacing.m),
        gap = HbTheme.spacing.m,
    ) {
        MenuRowIcon(item.icon ?: HbIcons.Check.takeIf { item.isChecked }, foreground)
        HbText(item.label, Modifier.weight(1f), style = HbTheme.typography.label, color = foreground, maxLines = 1)
        if (item.icon != null && item.isChecked) MenuRowIcon(HbIcons.Check, foreground)
        item.shortcut?.let { HbText(it, style = HbTheme.typography.caption, color = colors.textSecondary) }
    }
}

@Composable
private fun MenuRowIcon(icon: ImageVector?, tint: Color) {
    if (icon != null) {
        HbIcon(icon, null, Modifier.size(HbTheme.dimensions.iconSmallSize), tint = tint)
    } else {
        Spacer(Modifier.size(HbTheme.dimensions.iconSmallSize))
    }
}

@Composable
private fun menuRowBackground(isEnabled: Boolean, isPressed: Boolean, isActive: Boolean): Color {
    val colors = HbTheme.colors
    val motion = HbTheme.motion
    val target = when {
        !isEnabled -> Color.Transparent
        isPressed -> colors.pressedOverlay
        isActive -> colors.interactionHoverOverlay
        else -> Color.Transparent
    }
    return key(colors, isEnabled) {
        animateColorAsState(
            target,
            animationSpec = if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "menuItem",
        ).value
    }
}

private fun handleMenuSheetKey(
    event: KeyEvent,
    focused: Int,
    enabled: List<Int>,
    onDismiss: () -> Unit,
    onFocus: (Int) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.Escape) {
        onDismiss()
        return true
    }
    val next = composerMenuFocusIndex(event.key, event.isShiftPressed, focused, enabled) ?: return false
    if (next >= 0) onFocus(next)
    return true
}

/** Below the anchor, start-aligned; flips above and clamps inside the window when space runs out. */
internal class HbMenuPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left
        } else {
            anchorBounds.right - popupContentSize.width
        }
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = if (below + popupContentSize.height <= windowSize.height || above < 0) below else above
        return IntOffset(
            x = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
