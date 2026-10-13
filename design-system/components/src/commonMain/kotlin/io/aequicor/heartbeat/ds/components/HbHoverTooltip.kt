package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.coroutines.delay

/** Browser hover hints use Foundation; the popup never takes focus or covers its anchor's click target. */
@Composable
internal fun HbHoverTooltip(
    text: String,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    var isVisible by remember(text, isEnabled, isHovered) { mutableStateOf(false) }
    val delayMillis = HbTheme.motion.tooltipDelayMillis
    LaunchedEffect(isHovered, text, isEnabled, delayMillis) {
        if (isHovered && isEnabled && text.isNotBlank()) {
            delay(delayMillis.toLong())
            isVisible = true
        }
    }
    val gap = with(LocalDensity.current) { HbTheme.spacing.xs.roundToPx() }
    val position = remember(gap) { HoverTooltipPosition(gap) }
    Box(modifier.hoverable(interaction, enabled = isEnabled), propagateMinConstraints = true) {
        content()
        if (isVisible) {
            Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
                DisableSelection { HbTooltipLabel(text) }
            }
        }
    }
}

/** Anchor-relative placement keeps the hint inside the horizontal viewport and flips it at the bottom. */
internal class HoverTooltipPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            anchorBounds.top - gap - popupContentSize.height
        }
        return IntOffset(x, y)
    }
}
