package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

@Composable
internal fun ComposerMenuPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    isAccent: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    val shape = HbTheme.shapes.small
    val colors = HbTheme.colors
    val studio = HbTheme.surfaces
    val base = if (isAccent) studio.composerPillAccent else studio.composerPill
    val background = when {
        enabled && isPressed -> colors.pressedOverlay.compositeOver(base)
        enabled && isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    val foreground = when {
        !enabled -> colors.textSecondary
        isAccent -> studio.onComposerPillAccent
        else -> colors.textPrimary
    }
    HbRow(
        modifier = modifier
            .heightIn(min = maxOf(HbTheme.dimensions.touchTarget, HbTheme.dimensions.composerPillHeight))
            .hbFocusOutline(isFocused, shape)
            .background(background, shape)
            .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.xs),
        gap = HbTheme.spacing.xs,
    ) {
        if (icon != null) HbIcon(icon, null, tint = foreground)
        HbText(
            label,
            Modifier.widthIn(max = HbTheme.dimensions.composerLabelMaxWidth),
            style = HbTheme.typography.label,
            color = foreground,
            maxLines = 1,
        )
        HbIcon(HbIcons.ChevronDown, null, tint = foreground)
    }
}
