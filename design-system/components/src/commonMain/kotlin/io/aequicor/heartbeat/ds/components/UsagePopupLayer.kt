package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log

private val popupLog = Log.tag("DS/UsagePopup")

/**
 * A modal full-client layer consumes outside clicks while routing clicks on its own anchor back
 * to the two usage buttons. No underlying Send/editor/native surface is accidentally activated.
 * The common implementation preserves this behavior on desktop and touch hosts.
 */
@Composable
internal fun UsagePopupLayer(
    position: PopupPositionProvider,
    onDismiss: () -> Unit,
    onAnchorClick: (Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val anchor = remember { UsagePopupAnchor() }
    val dismiss by rememberUpdatedState(onDismiss)
    val click by rememberUpdatedState(onAnchorClick)
    Popup(anchor, onDismiss, PopupProperties(focusable = true)) {
        Layout(content = {
            Box(
                Modifier.pointerInput(anchor) {
                    detectTapGestures { point ->
                        val fraction = anchor.fractionAt(point)
                        if (fraction == null) dismiss() else click(fraction)
                    }
                },
            )
            Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) { content() }
        }) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val backdrop = measurables[0].measure(Constraints.fixed(width, height))
            val sheet = measurables[1].measure(Constraints(maxWidth = width, maxHeight = height))
            layout(width, height) {
                backdrop.place(0, 0)
                val bounds = anchor.bounds
                if (bounds != null) {
                    sheet.place(
                        position.calculatePosition(
                            bounds,
                            IntSize(width, height),
                            layoutDirection,
                            IntSize(sheet.width, sheet.height),
                        ),
                    )
                }
            }
        }
    }
}

private class UsagePopupAnchor : PopupPositionProvider {
    var bounds by mutableStateOf<IntRect?>(null)
    var direction: LayoutDirection = LayoutDirection.Ltr

    fun fractionAt(point: Offset): Float? {
        val rect = bounds ?: return null
        val isInside = rect.contains(IntOffset(point.x.toInt(), point.y.toInt()))
        if (!isInside || rect.width <= 0) return null
        val fraction = (point.x - rect.left) / rect.width
        return if (direction == LayoutDirection.Rtl) 1f - fraction else fraction
    }

    @HighFrequency
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        if (bounds != anchorBounds) {
            popupLog.v { "bounds: ${bounds?.toString().orEmpty()} -> $anchorBounds" }
            bounds = anchorBounds
        }
        direction = layoutDirection
        return IntOffset.Zero
    }
}
