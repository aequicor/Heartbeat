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

private val log = Log.tag("DS/MenuPopup")

/**
 * The desktop host uses WINDOW Compose layers. A click on a heavyweight native child (Chromium) never
 * reaches Compose, so an anchored layer would stay open. The menu layer covers the client area instead:
 * its transparent backdrop absorbs the outside click and dismisses, while the sheet keeps [position].
 * A non-[focusable] layer takes the same clicks but leaves keyboard focus in the window beneath.
 */
@Composable
internal actual fun HbMenuPopup(
    position: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    focusable: Boolean,
    content: @Composable () -> Unit,
) {
    val anchor = remember { HbMenuAnchor() }
    Popup(anchor, onDismissRequest, PopupProperties(focusable = focusable)) {
        HbDesktopMenuContent({ anchor.bounds }, position, onDismissRequest, content = content)
    }
}

/** Places the layer over the whole client area and records the anchor; Compose re-runs it when the anchor moves. */
internal class HbMenuAnchor : PopupPositionProvider {
    var bounds: IntRect? by mutableStateOf(null)
        private set

    @HighFrequency
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        if (bounds != anchorBounds) {
            log.v { "menu anchor moved: ${bounds != null} -> $anchorBounds" }
            bounds = anchorBounds
        }
        return IntOffset.Zero
    }
}

/**
 * Full-client menu layer: a pointer-only backdrop (no accessibility action, no focus stop) and the sheet
 * placed by [position] against the [anchor] in window coordinates. The sheet stays unplaced until the anchor is known.
 */
@Composable
internal fun HbDesktopMenuContent(
    anchor: () -> IntRect?,
    position: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    Layout(
        content = {
            Box(Modifier.pointerInput(Unit) { detectTapGestures { dismiss() } })
            // Sheet padding and dividers must not reach the backdrop; items handle their own taps.
            Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) { content() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val backdrop = measurables[0].measure(Constraints.fixed(width, height))
        val sheet = measurables[1].measure(Constraints(maxWidth = width, maxHeight = height))
        layout(width, height) {
            backdrop.place(0, 0)
            val bounds = anchor() ?: return@layout
            val size = IntSize(sheet.width, sheet.height)
            sheet.place(position.calculatePosition(bounds, IntSize(width, height), layoutDirection, size))
        }
    }
}
