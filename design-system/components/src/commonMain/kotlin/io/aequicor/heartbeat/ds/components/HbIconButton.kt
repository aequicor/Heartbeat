package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/IconButton")

/**
 * Quiet icon action with a complete hit target (touch size on mobile, dense control size on desktop).
 * [contentDescription] is always announced; [isSelected] marks the current destination of a rail or toolbar.
 * Every visual style shares this foundation implementation, like [HbTextField]: native kits have no
 * equivalent quiet icon control with the same hover, focus and selection semantics.
 */
@Composable
public fun HbIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isSelected: Boolean = false,
    size: Dp = HbTheme.dimensions.touchTarget,
) {
    val interactions = remember { MutableInteractionSource() }
    val isPressed by interactions.collectIsPressedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isFocused by interactions.collectIsFocusedAsState()
    val colors = HbTheme.colors
    val motion = HbTheme.motion
    val shape = HbTheme.shapes.medium
    val base = if (isSelected) colors.primaryContainer else Color.Transparent
    val target = when {
        !enabled -> base
        isPressed -> colors.pressedOverlay.compositeOver(base)
        isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    val background = key(colors, isSelected, enabled) {
        animateColorAsState(
            target,
            animationSpec = if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "iconButton",
        ).value
    }
    val tint = when {
        !enabled -> colors.textSecondary.copy(alpha = DISABLED_ALPHA)
        isSelected || isHovered -> colors.textPrimary
        else -> colors.textSecondary
    }
    Box(
        modifier = modifier
            .size(size)
            .semantics {
                this.contentDescription = contentDescription
                if (isSelected) selected = true
            }
            .clickable(interactions, indication = null, enabled = enabled, role = Role.Button) {
                log.i { "icon button pressed selected=$isSelected" }
                onClick()
            }
            .hbControlSurface(background, shape, isPressed = isPressed, isQuiet = true, isEnabled = enabled)
            .hbFocusOutline(isFocused, shape),
        contentAlignment = Alignment.Center,
    ) {
        HbIcon(icon, contentDescription = null, tint = tint)
    }
}

private const val DISABLED_ALPHA = 0.6f
