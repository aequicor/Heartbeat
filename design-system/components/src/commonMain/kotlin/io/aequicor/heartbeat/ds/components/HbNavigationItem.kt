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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/Navigation")

/**
 * Selectable row of a navigation list: destinations, projects and conversations.
 * [level] indents nested rows (for example conversations inside a project).
 * [isEmphasized] renders the label in a stronger weight, e.g. for unread content.
 * [supportingText] supplies a single-line preview below the label; [leadingContent] precedes the icon.
 * [selectedBackground] and [selectedForeground] allow a feature palette without changing interaction behavior.
 * [contentColor] sets ordinary labels and icons; emphasized rows use the primary text color.
 * [trailingContent] receives whether the row is active, so secondary actions can stay hidden until the
 * pointer or keyboard reaches the row: the row is active while hovered, while focus is on the row or any of
 * its nested actions, and after touch input on that row, which has no hover. Mobile rows start in touch mode.
 * Mouse input restores hover behavior even on a device that also has a touchscreen. Nested actions keep their own click
 * targets.
 * [role] is [Role.Button] for list entries; pass [Role.Tab] only inside a tab list.
 * All visual styles share this foundation row; it has no native counterpart in the platform kits.
 */
@Composable
public fun HbNavigationItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isSelected: Boolean = false,
    isEmphasized: Boolean = false,
    level: Int = 0,
    role: Role = Role.Button,
    supportingText: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    selectedBackground: Color = HbTheme.colors.selectedContainer,
    selectedForeground: Color = HbTheme.colors.textPrimary,
    contentColor: Color = HbTheme.colors.textSecondary,
    minHeight: Dp = HbTheme.dimensions.touchTarget,
    textStyle: TextStyle = HbTheme.typography.label,
    onSecondaryClick: (() -> Unit)? = null,
    trailingContent: @Composable RowScope.(isActive: Boolean) -> Unit = {},
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    // The row's own focus ends when Tab moves to a nested action; hasFocus keeps that action visible.
    var hasFocusWithin by remember { mutableStateOf(false) }
    var isTouch by remember { mutableStateOf(defaultNavigationUsesTouch()) }
    val onTouchChanged: (Boolean) -> Unit = remember { { isTouch = it } }
    val colors = HbTheme.colors
    val shape = RoundedCornerShape(HbTheme.dimensions.controlCornerRadius)
    val background = navigationBackground(isSelected, isHovered, isPressed, selectedBackground)
    val foreground = when {
        isSelected -> selectedForeground
        isEmphasized || supportingText != null -> colors.textPrimary
        else -> contentColor
    }
    val supportingForeground = if (isSelected) selectedForeground else colors.textSecondary
    val verticalPadding = if (supportingText != null) HbTheme.spacing.m else HbTheme.spacing.none
    HbRow(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = navigationMinHeight(minHeight))
            .semantics { if (isSelected) selected = true }
            .navigationPointerInput(onTouchChanged)
            .navigationSecondaryClick(onSecondaryClick)
            .hbFocusOutline(isFocused, shape)
            .onFocusChanged { hasFocusWithin = it.hasFocus }
            .clickable(interactionSource = interactions, indication = null, role = role) {
                log.i { "navigation item pressed level=$level selected=$isSelected" }
                onClick()
            }
            .background(background, shape)
            .padding(
                start = HbTheme.spacing.m + HbTheme.spacing.xl * level,
                end = HbTheme.spacing.xs,
                top = verticalPadding,
                bottom = verticalPadding,
            ),
        gap = HbTheme.spacing.l,
    ) {
        leadingContent?.invoke()
        if (icon != null) HbIcon(icon = icon, contentDescription = null, tint = foreground)
        NavigationItemLabel(
            label = label,
            supportingText = supportingText,
            isEmphasized = isEmphasized,
            isSelected = isSelected,
            textStyle = textStyle,
            foreground = foreground,
            supportingForeground = supportingForeground,
            modifier = Modifier.weight(1f),
        )
        trailingContent(isHovered || isFocused || hasFocusWithin || isTouch)
    }
}

@Composable
private fun NavigationItemLabel(
    label: String,
    supportingText: String?,
    isEmphasized: Boolean,
    isSelected: Boolean,
    textStyle: TextStyle,
    foreground: Color,
    supportingForeground: Color,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier = modifier, gap = HbTheme.spacing.xxs) {
        HbText(
            text = label,
            style = textStyle.copy(
                fontWeight = when {
                    isEmphasized -> FontWeight.SemiBold
                    isSelected -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
            ),
            color = foreground,
            maxLines = if (supportingText != null) 2 else 1,
            isOverflowTooltipEnabled = true,
        )
        if (supportingText != null) {
            HbText(
                text = supportingText,
                style = HbTheme.typography.caption,
                color = supportingForeground,
                maxLines = 1,
            )
        }
    }
}

/**
 * Section title of a navigation list. With [onToggle] it becomes a disclosure control announcing
 * [isExpanded]; [trailingContent] hosts section actions such as "new item".
 */
@Composable
public fun HbNavigationHeader(
    title: String,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = true,
    expandedLabel: String = "",
    collapsedLabel: String = "",
    onToggle: (() -> Unit)? = null,
    isChevronAlwaysVisible: Boolean = false,
    textStyle: TextStyle = HbTheme.typography.caption,
    contentColor: Color = HbTheme.colors.textSecondary,
    minHeight: Dp = HbTheme.dimensions.touchTarget,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val colors = HbTheme.colors
    val shape = RoundedCornerShape(HbTheme.dimensions.controlCornerRadius)
    val toggleModifier = if (onToggle == null) {
        Modifier
    } else {
        Modifier
            .semantics { stateDescription = if (isExpanded) expandedLabel else collapsedLabel }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button) {
                log.i { "navigation section expanded=${!isExpanded}" }
                onToggle()
            }
    }
    HbRow(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = navigationMinHeight(minHeight))
            .semantics { heading() }
            .hbFocusOutline(isFocused, shape)
            .then(toggleModifier)
            .padding(start = HbTheme.spacing.m, end = HbTheme.spacing.xs),
        gap = HbTheme.spacing.xs,
    ) {
        HbText(
            text = title,
            modifier = Modifier.weight(1f),
            style = textStyle,
            color = contentColor,
            maxLines = 1,
        )
        val isChevronVisible = isChevronAlwaysVisible || isHovered || isFocused || !isExpanded
        if (onToggle != null && isChevronVisible) {
            HbIcon(
                icon = if (isExpanded) HbIcons.ChevronUp else HbIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(HbTheme.dimensions.iconSmallSize),
                tint = colors.textSecondary,
            )
        }
        trailingContent()
    }
}

@Composable
private fun navigationBackground(
    isSelected: Boolean,
    isHovered: Boolean,
    isPressed: Boolean,
    selectedBackground: Color,
): Color {
    val colors = HbTheme.colors
    val base = if (isSelected) selectedBackground else Color.Transparent
    val target = when {
        isPressed -> colors.pressedOverlay.compositeOver(base)
        isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    val motion = HbTheme.motion
    return key(colors, isSelected, selectedBackground) {
        animateColorAsState(
            targetValue = target,
            animationSpec = if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "navigationBackground",
        ).value
    }
}

@Composable
@ReadOnlyComposable
private fun navigationMinHeight(requested: Dp): Dp = controlTargetSize(requested)
