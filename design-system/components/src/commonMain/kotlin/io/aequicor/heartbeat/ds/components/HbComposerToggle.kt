package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/ComposerToggle")

/**
 * A composer mode switch such as "Research": a compact labelled pill in the composer toolbar. When [isChecked]
 * the pill takes the studio's selected tint, so the active mode reads at a glance next to the model menu.
 */
@Composable
public fun HbComposerToggle(
    label: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val isPressed by interactions.collectIsPressedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isFocused by interactions.collectIsFocusedAsState()
    val colors = HbTheme.colors
    val studio = HbTheme.surfaces
    val motion = HbTheme.motion
    val shape = HbTheme.shapes.small
    val base = if (isChecked) studio.selected else studio.composerPill
    val target = when {
        !enabled -> base
        isPressed -> colors.pressedOverlay.compositeOver(base)
        isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    val background = key(colors, isChecked) {
        animateColorAsState(
            target,
            animationSpec = if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "composerToggle",
        ).value
    }
    val foreground = when {
        !enabled -> colors.textSecondary
        isChecked -> studio.onSelected
        else -> colors.textPrimary
    }
    HbRow(
        modifier = modifier
            .heightIn(min = controlTargetSize(HbTheme.dimensions.composerActionSize))
            .hbFocusOutline(isFocused, shape)
            .toggleable(isChecked, interactions, indication = null, enabled = enabled, role = Role.Switch) {
                log.i { "composer toggle changed checked=$it" }
                onCheckedChange(it)
            }
            .hbControlSurface(background, shape)
            .padding(horizontal = HbTheme.spacing.m),
        gap = HbTheme.spacing.xs,
    ) {
        if (icon != null) {
            HbIcon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(HbTheme.dimensions.iconSize),
                tint = foreground,
            )
        }
        HbText(label, style = HbTheme.typography.label, color = foreground, maxLines = 1)
    }
}
