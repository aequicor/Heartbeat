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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.theme.HbVisualStyle

/** Circular composer action with the same hit target, keyboard behavior and fill as the studio menu trigger. */
@Composable
public fun HbComposerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltipText: String? = contentDescription,
) {
    ComposerIconButton(
        icon = icon,
        label = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tooltipText = tooltipText,
        shape = if (HbTheme.studioDimensions.isDesktop) HbTheme.shapes.small else CircleShape,
        size = HbTheme.studioDimensions.composerActionSize,
        background = HbTheme.studioColors.composerPill,
        iconSize = if (HbTheme.studioDimensions.isDesktop) {
            HbTheme.dimensions.iconSize
        } else {
            HbTheme.dimensions.iconLargeSize
        },
    )
}

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
    shape: Shape = HbTheme.shapes.medium,
    size: Dp = HbTheme.dimensions.touchTarget,
    background: Color? = null,
    tint: Color? = null,
    iconSize: Dp = HbTheme.dimensions.iconSize,
    tooltipText: String? = label,
) {
    val interactions = remember { MutableInteractionSource() }
    val isPressed by interactions.collectIsPressedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isFocused by interactions.collectIsFocusedAsState()
    val colors = HbTheme.colors
    val motion = HbTheme.motion
    val base = background ?: composerButtonFill(isPrimary, enabled)
    val target = when {
        !enabled -> base
        isPressed -> colors.pressedOverlay.compositeOver(base)
        isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    val animatedBackground = key(colors, isPrimary, enabled, background) {
        animateColorAsState(
            target,
            animationSpec = if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "composerAction",
        ).value
    }
    val foreground = tint ?: when {
        !enabled -> colors.textSecondary
        isPrimary -> colors.onPrimary
        else -> colors.textPrimary
    }
    Box(
        modifier = modifier
            .size(controlTargetSize(size))
            .semantics { contentDescription = label }
            .hbFocusOutline(isFocused, shape)
            .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .hbControlSurface(animatedBackground, shape),
        contentAlignment = Alignment.Center,
    ) {
        HbTooltip(tooltipText.orEmpty(), Modifier.fillMaxSize(), isEnabled = tooltipText != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ComposerButtonIcon(icon, fallbackSymbol, foreground, iconSize)
            }
        }
    }
}

@Composable
@ReadOnlyComposable
private fun composerButtonFill(isPrimary: Boolean, enabled: Boolean): Color {
    val colors = HbTheme.colors
    return when {
        isPrimary && enabled -> colors.primary
        !isPrimary && HbTheme.visualStyle == HbVisualStyle.Glass -> Color.Transparent
        else -> colors.buttonFill
    }
}

@Composable
private fun ComposerButtonIcon(icon: ImageVector?, fallbackSymbol: String, foreground: Color, iconSize: Dp) {
    if (icon != null) {
        HbIcon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = foreground)
    } else {
        HbText(
            text = fallbackSymbol,
            modifier = Modifier.clearAndSetSemantics {},
            style = HbTheme.typography.title,
            color = foreground,
        )
    }
}
