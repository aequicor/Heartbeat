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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/ComposerMenuItem")

@Composable
internal fun ComposerMenuItem(
    action: HbComposerAction,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    val colors = HbTheme.colors
    val motion = HbTheme.motion
    val shape = HbTheme.shapes.medium
    val targetBackground = when {
        !action.isEnabled -> Color.Transparent
        isPressed -> colors.pressedOverlay
        isHovered -> colors.interactionHoverOverlay
        else -> Color.Transparent
    }
    val background = key(colors, action.isEnabled) {
        animateColorAsState(
            targetBackground,
            animationSpec = if (motion.isReducedMotion) snap() else tween(motion.fastMillis),
            label = "composerMenuItem",
        ).value
    }
    HbColumn(
        modifier = modifier
            .heightIn(min = HbTheme.dimensions.touchTarget)
            .semantics { selected = action.isSelected }
            .onFocusChanged { state ->
                if (state.isFocused) {
                    log.d { "composer command focused" }
                    onFocus()
                }
            }
            .hbFocusOutline(isFocused, shape)
            .clickable(
                interactions,
                indication = null,
                enabled = action.isEnabled,
                role = Role.Button,
                onClick = onClick,
            )
            .background(background, shape)
            .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.s),
        gap = HbTheme.spacing.xs,
    ) {
        HbRow(gap = HbTheme.spacing.s) {
            if (action.isSelected) HbIcon(HbIcons.Check, null, tint = colors.textPrimary)
            HbText(
                text = action.label,
                style = HbTheme.typography.label,
                color = if (action.isEnabled) colors.textPrimary else colors.textSecondary,
            )
        }
        if (action.supportingText != null) {
            HbText(action.supportingText, style = HbTheme.typography.caption, color = colors.textSecondary)
        }
    }
}
