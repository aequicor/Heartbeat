package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Foundation switch with keyboard focus and mobile-sized hit area, shared by all visual kits.
 * The checked track uses the brand accent with its accessible thumb color; unchecked stays neutral.
 */
@Composable
fun HbSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val isHovered by interaction.collectIsHoveredAsState()
    val isPressed by interaction.collectIsPressedAsState()
    val colors = HbTheme.colors
    val alpha = if (enabled) 1f else 0.45f
    val track = (if (checked) colors.brand else colors.outlineSubtle).copy(alpha = alpha)
    val thumb = (if (checked) colors.onBrand else colors.textSecondary).copy(alpha = alpha)
    val feedback = when {
        !enabled -> Color.Transparent
        isPressed -> colors.pressedOverlay
        isHovered -> colors.interactionHoverOverlay
        else -> Color.Transparent
    }
    Box(
        modifier.size(HbTheme.dimensions.touchTarget)
            .background(feedback, HbTheme.shapes.small)
            .semantics { contentDescription = label }
            .hbFocusOutline(isFocused, HbTheme.shapes.small)
            .toggleable(checked, interaction, indication = null, enabled, Role.Switch) {
                Log.tag("DS/Controls").i { "switch pressed" }
                onCheckedChange(it)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(HbTheme.dimensions.switchWidth, HbTheme.dimensions.switchHeight)) {
            drawRoundRect(
                track,
                cornerRadius = CornerRadius(size.height / 2f),
            )
            val radius = size.height * 0.36f
            drawCircle(
                thumb,
                radius,
                Offset(if (checked) size.width - size.height / 2f else size.height / 2f, size.height / 2f),
            )
        }
    }
}

@Preview
@Composable
private fun HbSwitchLightPreview() {
    HbTheme(darkTheme = false) { HbSwitch(true, {}, "Cinematic intro") }
}

@Preview
@Composable
private fun HbSwitchDarkPreview() {
    HbTheme(darkTheme = true) { HbSwitch(false, {}, "Cinematic intro") }
}
