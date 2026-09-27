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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/Chip")

/**
 * Compact context label with an optional leading [icon], e.g. a project, environment or branch.
 * With [onClick] the chip becomes a button and shows [trailingIcon] (a disclosure chevron for menus);
 * without it the chip is plain, non-focusable information. [accessibleLabel] replaces the visible label
 * for assistive technologies when the label alone lacks context.
 */
@Composable
public fun HbChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailingIcon: ImageVector? = if (onClick != null) HbIcons.ChevronDown else null,
    accessibleLabel: String = label,
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    val colors = HbTheme.colors
    val shape = HbTheme.shapes.small
    val target = when {
        onClick == null -> Color.Transparent
        isPressed -> colors.pressedOverlay
        isHovered -> colors.interactionHoverOverlay
        else -> Color.Transparent
    }
    val motion = HbTheme.motion
    val background = key(colors) {
        animateColorAsState(
            target,
            animationSpec = if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "chip",
        ).value
    }
    val action = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(interactions, indication = null, role = Role.Button) {
            log.i { "chip pressed" }
            onClick()
        }
    }
    HbRow(
        modifier = modifier
            .heightIn(min = HbTheme.dimensions.controlHeight)
            .semantics(mergeDescendants = true) { contentDescription = accessibleLabel }
            .then(action)
            .background(background, shape)
            .hbFocusOutline(isFocused, shape)
            .padding(horizontal = HbTheme.spacing.s, vertical = HbTheme.spacing.xxs),
        gap = HbTheme.spacing.xs,
    ) {
        if (icon != null) {
            HbIcon(icon, null, Modifier.size(HbTheme.dimensions.iconSmallSize), tint = colors.textSecondary)
        }
        HbText(label, style = HbTheme.typography.caption, color = colors.textPrimary, maxLines = 1)
        if (trailingIcon != null) {
            HbIcon(trailingIcon, null, Modifier.size(HbTheme.dimensions.iconSmallSize), tint = colors.textSecondary)
        }
    }
}
