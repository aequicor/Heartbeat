package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.ImmutableList

/**
 * One row of the composer suggestion list. [sectionLabel] on the first row of a group names it (for example
 * "Commands" or "Files"); [isEnabled] marks rows the current context cannot accept, [isSelected] the row
 * keyboard navigation points at.
 */
@Immutable
public data class HbComposerSuggestion(
    public val id: String,
    public val label: String,
    public val supportingText: String? = null,
    public val sectionLabel: String? = null,
    public val icon: ImageVector? = null,
    public val isEnabled: Boolean = true,
    public val isSelected: Boolean = false,
)

/** Suggestions currently offered above the composer; [selectedIndex] follows keyboard navigation. */
@Immutable
public data class HbComposerSuggestions(
    public val items: ImmutableList<HbComposerSuggestion>,
    public val selectedIndex: Int = 0,
)

/**
 * Suggestion list floating above the whole composer while its editor keeps the focus. Unlike the composer
 * menus this popup is not focusable: arrows, Enter, Tab and Escape stay editor keys handled by
 * [composerSuggestionKeys] before the send shortcut, so accepting a suggestion never sends the draft.
 */
@Composable
internal fun ComposerSuggestionsPopup(
    suggestions: HbComposerSuggestions,
    label: String,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
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
    val listWidth = minOf(dimensions.composerMenuMaxWidth, availableWidth.coerceAtLeast(dimensions.touchTarget))
    val listHeight = minOf(dimensions.composerMenuMaxHeight, availableHeight.coerceAtLeast(dimensions.touchTarget))
    val position = with(density) {
        remember(anchor, gutter, dimensions.composerMenuOffset, this) {
            ComposerPopupPosition(anchor, gutter.roundToPx(), dimensions.composerMenuOffset.roundToPx())
        }
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        HbColumn(
            modifier = modifier
                .padding(gutter)
                .width(listWidth)
                .heightIn(max = listHeight)
                .semantics { paneTitle = label }
                .background(HbTheme.colors.surfaceElevated, HbTheme.shapes.large)
                .hbPopupSurface(HbTheme.colors.surface, HbTheme.shapes.medium)
                .hbVerticalScroll(rememberScrollState())
                .padding(HbTheme.spacing.s),
            gap = HbTheme.spacing.xs,
        ) {
            suggestions.items.forEachIndexed { index, item ->
                if (item.sectionLabel != null) ComposerSectionLabel(item.sectionLabel)
                SuggestionRow(item, onClick = { onSelect(index) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SuggestionRow(item: HbComposerSuggestion, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    val colors = HbTheme.colors
    val motion = HbTheme.motion
    val shape = HbTheme.shapes.medium
    val targetBackground = when {
        !item.isEnabled -> Color.Transparent
        isPressed -> colors.pressedOverlay
        isHovered -> colors.interactionHoverOverlay
        else -> Color.Transparent
    }
    val background = key(colors, item.isEnabled) {
        animateColorAsState(
            targetBackground,
            animationSpec = if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "composerSuggestionRow",
        ).value
    }
    HbRow(
        modifier = modifier
            .heightIn(min = HbTheme.dimensions.touchTarget)
            .semantics { selected = item.isSelected }
            .clickable(interactions, indication = null, enabled = item.isEnabled, role = Role.Button, onClick = onClick)
            .background(background, shape)
            .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.s),
        gap = HbTheme.spacing.s,
    ) {
        if (item.icon != null) {
            HbIcon(item.icon, contentDescription = null, tint = colors.textSecondary)
        }
        HbColumn(gap = HbTheme.spacing.xxs, modifier = Modifier.weight(1f)) {
            HbText(
                text = item.label,
                style = HbTheme.typography.label,
                color = if (item.isEnabled) colors.textPrimary else colors.textSecondary,
            )
            if (item.supportingText != null) {
                HbText(item.supportingText, style = HbTheme.typography.caption, color = colors.textSecondary)
            }
        }
    }
}

/** Keyboard state resolved before the send shortcut: whether the editor owns suggestion navigation keys. */
internal enum class ComposerSuggestionKeys { Idle, Navigate, Accept, Dismiss }

/**
 * Resolves a key pressed while suggestions are shown. [Navigate] (arrows) moves the selection, [Accept]
 * (Enter without Shift, Tab without modifiers) takes it, [Dismiss] (Escape) closes the list; every other
 * key keeps the editor's own behaviour.
 */
internal fun composerSuggestionKey(
    key: Key,
    isShiftPressed: Boolean,
    isCtrlPressed: Boolean,
    isMetaPressed: Boolean,
    hasSuggestions: Boolean,
): ComposerSuggestionKeys {
    if (!hasSuggestions) return ComposerSuggestionKeys.Idle
    val hasCommandModifier = isCtrlPressed || isMetaPressed
    return when {
        key == Key.Escape -> ComposerSuggestionKeys.Dismiss

        key == Key.DirectionUp || key == Key.DirectionDown -> if (hasCommandModifier) {
            ComposerSuggestionKeys.Idle
        } else {
            ComposerSuggestionKeys.Navigate
        }

        (key == Key.Enter || key == Key.NumPadEnter) && !isShiftPressed && !hasCommandModifier ->
            ComposerSuggestionKeys.Accept

        key == Key.Tab && !hasCommandModifier -> ComposerSuggestionKeys.Accept

        else -> ComposerSuggestionKeys.Idle
    }
}

/** Consumes the handled key phases so neither the send shortcut nor focus traversal sees them. */
internal fun Modifier.composerSuggestionKeys(
    hasSuggestions: Boolean,
    onNavigate: (Int) -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val resolved = composerSuggestionKey(
        event.key,
        event.isShiftPressed,
        event.isCtrlPressed,
        event.isMetaPressed,
        hasSuggestions,
    )
    if (resolved == ComposerSuggestionKeys.Idle) return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyDown) {
        when (resolved) {
            ComposerSuggestionKeys.Navigate -> onNavigate(if (event.key == Key.DirectionUp) -1 else 1)
            ComposerSuggestionKeys.Accept -> onAccept()
            ComposerSuggestionKeys.Dismiss -> onDismiss()
            ComposerSuggestionKeys.Idle -> Unit
        }
    }
    true
}
