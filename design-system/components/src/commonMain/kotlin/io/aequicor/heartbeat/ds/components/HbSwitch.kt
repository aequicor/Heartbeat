package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.theme.HbTheme

/** Foundation switch with keyboard focus and mobile-sized hit area, shared by all visual kits. */
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
    val colors = HbTheme.colors
    val alpha = if (enabled) 1f else 0.45f
    val track = (if (checked) colors.primary else colors.outlineSubtle).copy(alpha = alpha)
    val thumb = (if (checked) colors.onPrimary else colors.textSecondary).copy(alpha = alpha)
    Box(
        modifier.size(HbTheme.dimensions.touchTarget)
            .semantics { contentDescription = label }
            .toggleable(checked, interaction, indication = null, enabled, Role.Switch) {
                Log.tag("DS/Controls").i { "switch pressed" }
                onCheckedChange(it)
            }.hbFocusOutline(isFocused, HbTheme.shapes.small),
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
