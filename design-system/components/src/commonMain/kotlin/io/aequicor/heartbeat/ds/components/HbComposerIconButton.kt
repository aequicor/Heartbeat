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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.theme.HbVisualStyle

/** A compact icon action whose complete touch target and accessible label stay stable. */
@Composable
internal fun ComposerIconButton(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = false,
    fallbackSymbol: String = "",
) {
    val interactions = remember { MutableInteractionSource() }
    val isPressed by interactions.collectIsPressedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isFocused by interactions.collectIsFocusedAsState()
    val colors = HbTheme.colors
    val motion = HbTheme.motion
    val shape = HbTheme.shapes.medium
    val base = when {
        isPrimary && enabled -> colors.primary
        !isPrimary && HbTheme.visualStyle == HbVisualStyle.Glass -> Color.Transparent
        else -> colors.buttonFill
    }
    val target = when {
        !enabled -> base
        isPressed -> colors.pressedOverlay.compositeOver(base)
        isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    val background = key(colors, isPrimary, enabled) {
        animateColorAsState(
            target,
            animationSpec = if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "composerAction",
        ).value
    }
    val foreground = when {
        !enabled -> colors.textSecondary
        isPrimary -> colors.onPrimary
        else -> colors.textPrimary
    }
    Box(
        modifier = modifier
            .size(HbTheme.dimensions.touchTarget)
            .semantics { contentDescription = label }
            .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .hbControlSurface(background, shape, isPressed = isPressed, isQuiet = !isPrimary, isEnabled = enabled)
            .hbFocusOutline(isFocused, shape),
        contentAlignment = Alignment.Center,
    ) {
        ComposerButtonIcon(icon, fallbackSymbol, foreground)
    }
}

@Composable
private fun ComposerButtonIcon(icon: ImageVector?, fallbackSymbol: String, foreground: Color) {
    if (icon != null) {
        HbIcon(icon, contentDescription = null, tint = foreground)
    } else {
        HbText(
            text = fallbackSymbol,
            modifier = Modifier.clearAndSetSemantics {},
            style = HbTheme.typography.title,
            color = foreground,
        )
    }
}
