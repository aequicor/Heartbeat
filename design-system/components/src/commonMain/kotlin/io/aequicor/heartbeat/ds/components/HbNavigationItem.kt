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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/Navigation")

/**
 * Selectable row of a navigation list: destinations, projects and conversations.
 * [level] indents nested rows (for example conversations inside a project).
 * [isEmphasized] renders the label in a stronger weight, e.g. for unread content.
 * [trailingContent] receives whether the row is active, so secondary actions can stay hidden until the
 * pointer or keyboard reaches the row: the row is active while hovered, while focus is on the row or any of
 * its nested actions, and always under touch input, which has no hover. Nested actions keep their own click
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
    trailingContent: @Composable RowScope.(isActive: Boolean) -> Unit = {},
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    // The row's own focus ends when Tab moves to a nested action; hasFocus keeps that action visible.
    var hasFocusWithin by remember { mutableStateOf(false) }
    val isTouch = LocalInputModeManager.current.inputMode == InputMode.Touch
    val colors = HbTheme.colors
    val shape = HbTheme.shapes.small
    val background = navigationBackground(isSelected, isHovered, isPressed)
    val foreground = if (isSelected || isEmphasized) colors.textPrimary else colors.textSecondary
    HbRow(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HbTheme.dimensions.touchTarget)
            .semantics { if (isSelected) selected = true }
            .onFocusChanged { hasFocusWithin = it.hasFocus }
            .clickable(interactionSource = interactions, indication = null, role = role) {
                log.i { "navigation item pressed level=$level selected=$isSelected" }
                onClick()
            }
            .background(background, shape)
            .hbFocusOutline(isFocused, shape)
            .padding(
                start = HbTheme.spacing.m + HbTheme.spacing.xl * level,
                end = HbTheme.spacing.xs,
            ),
        gap = HbTheme.spacing.s,
    ) {
        if (icon != null) HbIcon(icon = icon, contentDescription = null, tint = foreground)
        HbText(
            text = label,
            modifier = Modifier.weight(1f),
            style = if (isEmphasized) {
                HbTheme.typography.label.copy(fontWeight = FontWeight.SemiBold)
            } else {
                HbTheme.typography.label.copy(fontWeight = FontWeight.Normal)
            },
            color = foreground,
            maxLines = 1,
        )
        trailingContent(isHovered || isFocused || hasFocusWithin || isTouch)
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
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val colors = HbTheme.colors
    val shape = HbTheme.shapes.small
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
            .heightIn(min = HbTheme.dimensions.touchTarget)
            .semantics { heading() }
            .then(toggleModifier)
            .hbFocusOutline(isFocused, shape)
            .padding(start = HbTheme.spacing.m, end = HbTheme.spacing.xs),
        gap = HbTheme.spacing.xs,
    ) {
        HbText(
            text = title,
            modifier = Modifier.weight(1f, fill = false),
            style = HbTheme.typography.caption,
            color = colors.textSecondary,
            maxLines = 1,
        )
        val isChevronVisible = isHovered || isFocused || !isExpanded
        if (onToggle != null && isChevronVisible) {
            HbIcon(
                icon = if (isExpanded) HbIcons.ChevronDown else HbIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(HbTheme.dimensions.iconSmallSize),
                tint = colors.textSecondary,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        trailingContent()
    }
}

@Composable
private fun navigationBackground(isSelected: Boolean, isHovered: Boolean, isPressed: Boolean): Color {
    val colors = HbTheme.colors
    val base = if (isSelected) colors.primaryContainer else Color.Transparent
    val target = when {
        isPressed -> colors.pressedOverlay.compositeOver(base)
        isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    val motion = HbTheme.motion
    return key(colors, isSelected) {
        animateColorAsState(
            targetValue = target,
            animationSpec = if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "navigationBackground",
        ).value
    }
}
