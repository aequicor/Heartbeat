package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.BrowserPaneMode

/**
 * One stable browser composition slot; resizing and expanding never replace its native viewport.
 */
@Composable
internal fun StudioBrowserLayout(
    mode: BrowserPaneMode,
    modifier: Modifier = Modifier,
    browser: @Composable () -> Unit = {},
    chat: @Composable () -> Unit,
) {
    var fraction by remember { mutableFloatStateOf(0.45f) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val effectiveMode = mode.forWidth(maxWidth, HbTheme.dimensions.settingsMaxWidth * 2)
        val widthPixels = with(density) { maxWidth.toPx() }
        val browserWidth = effectiveMode.width(maxWidth, fraction)
        HbRow(Modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
            if (effectiveMode != BrowserPaneMode.Expanded) {
                Box(Modifier.weight(1f).fillMaxHeight()) { chat() }
            }
            if (effectiveMode == BrowserPaneMode.Panel) {
                BrowserPaneDivider { delta ->
                    fraction = resizedFraction(fraction, delta, widthPixels)
                }
            }
            Box(Modifier.width(browserWidth).fillMaxHeight().testTag("browser-panel")) {
                if (mode != BrowserPaneMode.Hidden) browser()
            }
        }
    }
}

private fun BrowserPaneMode.forWidth(available: Dp, minimum: Dp): BrowserPaneMode =
    if (this == BrowserPaneMode.Panel && available < minimum) BrowserPaneMode.Expanded else this

private fun BrowserPaneMode.width(available: Dp, fraction: Float): Dp = when (this) {
    BrowserPaneMode.Hidden -> available * 0f
    BrowserPaneMode.Panel -> available * fraction
    BrowserPaneMode.Expanded -> available
}

private fun resizedFraction(current: Float, delta: Float, width: Float): Float =
    if (width > 0) (current - delta / width).coerceIn(MIN_PANE_FRACTION, MAX_PANE_FRACTION) else current

@Composable
private fun BrowserPaneDivider(onResize: (Float) -> Unit) {
    Box(
        Modifier.fillMaxHeight()
            .width(HbTheme.spacing.s)
            .background(HbTheme.colors.outline)
            .testTag("browser-resize")
            .draggable(rememberDraggableState(onResize), Orientation.Horizontal),
    )
}

private const val MIN_PANE_FRACTION = 0.25f
private const val MAX_PANE_FRACTION = 0.75f
