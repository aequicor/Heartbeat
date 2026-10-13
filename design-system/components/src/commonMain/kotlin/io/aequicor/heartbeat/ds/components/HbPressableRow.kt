package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/PressableRow")

/**
 * A row of cells that acts as one button, such as a table row that opens its details. Shared Foundation rendering
 * keeps the studio's flat style on all hosts, like [HbSettingsRow]: quiet hover and pressed fills, a keyboard-only
 * focus ring and the [Role.Button] role; dense on desktop, touch-sized on mobile. Without [onClick] it is a plain row
 * of the same height, so pressable and plain rows line up in one table.
 */
@Composable
public fun HbPressableRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gap: Dp = HbTheme.spacing.m,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(HbTheme.dimensions.controlCornerRadius)
    val background = settingsRowBackground(interactions, isInteractive = onClick != null && enabled, isSelected = false)
    val action = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .hbFocusOutline(isFocused, shape)
            .clickable(interactions, null, enabled, role = Role.Button) {
                log.i { "pressable row pressed" }
                onClick()
            }
    }
    HbRow(
        modifier.fillMaxWidth()
            .heightIn(min = controlTargetSize(HbTheme.dimensions.compactControlHeight))
            .then(action)
            .background(background, shape)
            .padding(horizontal = HbTheme.spacing.s, vertical = HbTheme.spacing.xs),
        gap = gap,
        content = content,
    )
}
