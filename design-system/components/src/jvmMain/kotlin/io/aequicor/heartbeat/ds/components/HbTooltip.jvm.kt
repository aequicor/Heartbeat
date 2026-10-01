package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Anchored to the control bounds instead of the cursor: a cursor-placed hint near the window bottom flips onto
 * the pointer and swallows the next click, so the hint must never overlap its own control.
 *
 * The hint is composed in a popup layer, which inherits composition locals from the hovered control, including
 * the `LocalSelectionRegistrar` of an enclosing [androidx.compose.foundation.text.selection.SelectionContainer].
 * Its text would register with that container while laid out in another scene, so the next press inside the
 * container would compare two hierarchies and crash with "layouts are not part of the same hierarchy".
 * A hint is never selectable: it is composed with selection disabled.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun HbPlatformTooltip(
    text: String,
    modifier: Modifier,
    isEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (isEnabled && text.isNotBlank()) {
        TooltipArea(
            tooltip = { DisableSelection { HbTooltipLabel(text) } },
            modifier = modifier,
            delayMillis = HbTheme.motion.tooltipDelayMillis,
            tooltipPlacement = TooltipPlacement.ComponentRect(
                anchor = Alignment.BottomCenter,
                alignment = Alignment.BottomCenter,
                offset = DpOffset(x = HbTheme.spacing.none, y = HbTheme.spacing.xs),
            ),
            content = content,
        )
    } else {
        Box(modifier, propagateMinConstraints = true) { content() }
    }
}
