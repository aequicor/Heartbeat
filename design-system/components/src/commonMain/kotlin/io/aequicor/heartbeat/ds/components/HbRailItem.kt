package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme

private val railLog = Log.tag("DS/RailItem")

/** A single keyboard and touch target for a rail icon and its label, shared by all platform styles. */
@Composable
fun HbRailItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    val shape = RoundedCornerShape(HbTheme.dimensions.controlCornerRadius)
    val colors = HbTheme.colors
    val base = if (isSelected) colors.selectedContainer else Color.Transparent
    val background = when {
        isPressed -> colors.pressedOverlay.compositeOver(base)
        isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    HbColumn(
        modifier.heightIn(min = HbTheme.dimensions.touchTarget)
            .hbFocusOutline(isFocused, shape)
            .background(background, shape)
            .semantics { selected = isSelected }
            .clickable(interactions, indication = null, role = Role.Button) {
                railLog.i { "rail destination pressed selected=$isSelected" }
                onClick()
            }
            .padding(horizontal = HbTheme.spacing.xs, vertical = HbTheme.spacing.m),
        gap = HbTheme.spacing.xs,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HbIcon(icon, contentDescription = null, Modifier.size(HbTheme.dimensions.iconLargeSize))
        HbText(
            label,
            style = HbTheme.typography.caption.copy(
                textAlign = TextAlign.Center,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            ),
            color = colors.textPrimary,
            maxLines = 2,
        )
    }
}
