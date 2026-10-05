package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Stateless checkbox/radio row. Shared Foundation rendering preserves the studio's flat style on all hosts;
 * dimensions come from the host preset, while selectable/toggleable provide native keyboard and accessibility roles.
 */
@Composable
public fun HbChoiceRow(
    label: String,
    selected: Boolean,
    isMultiple: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val isHovered by interaction.collectIsHoveredAsState()
    val isPressed by interaction.collectIsPressedAsState()
    val feedback = when {
        !enabled -> Color.Transparent
        isPressed -> HbTheme.colors.pressedOverlay
        isHovered -> HbTheme.colors.interactionHoverOverlay
        else -> Color.Transparent
    }
    val row = modifier.background(feedback, HbTheme.shapes.small)
    val shape = if (isMultiple) HbTheme.shapes.small else CircleShape
    val color = if (enabled) HbTheme.colors.textPrimary else HbTheme.colors.textSecondary
    val control = if (isMultiple) {
        row.hbFocusOutline(
            isFocused,
            HbTheme.shapes.small,
        ).toggleable(
            selected,
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { onClick() },
        )
    } else {
        row.hbFocusOutline(
            isFocused,
            HbTheme.shapes.small,
        ).selectable(
            selected,
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        )
    }
    HbRow(
        control.fillMaxWidth().heightIn(min = HbTheme.dimensions.touchTarget).padding(HbTheme.spacing.s),
        gap = HbTheme.spacing.m,
    ) {
        Box(
            Modifier.size(HbTheme.dimensions.iconSize).border(HbTheme.dimensions.borderWidth, color, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                if (isMultiple) {
                    HbIcon(HbIcons.Check, null, tint = color)
                } else {
                    Box(Modifier.size(HbTheme.dimensions.statusDotSize).background(color, CircleShape))
                }
            }
        }
        HbText(label, color = color)
    }
}
