package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import io.aequicor.heartbeat.ds.components.LocalHbNativeContentViewport
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Keeps a bounded document's full layout footprint while placing its full-size content inside the
 * unobstructed viewport. The smaller clipping layout lets SwingPanel's own interop holder position
 * its native clip group; no additional Swing hierarchy participates in Chromium's initial painting.
 * Coordinates remain Compose pixels, snapped inward to the AWT grid; SwingPanel performs the final
 * native density conversion.
 */
@Composable
internal fun DocumentViewport(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val host = LocalHbNativeContentViewport.current
    val density = LocalDensity.current.density
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var clippedBounds by remember { mutableStateOf(Rect.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val visible = if (host == null) clippedBounds else clippedBounds.intersect(host)
    val clip = documentViewportBounds(bounds, visible, density, rootOrigin)
    Layout(
        modifier = modifier.fillMaxSize().onGloballyPositioned {
            bounds = it.boundsInWindow(clipBounds = false)
            clippedBounds = it.boundsInWindow()
            rootOrigin = bounds.topLeft - it.findRootCoordinates().localBoundingBoxOf(it, clipBounds = false).topLeft
        },
        content = {
            Layout(modifier = Modifier.clipToBounds(), content = content) { measurables, constraints ->
                val full = measurables.single().measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight))
                val region = clip.intersect(IntRect(0, 0, full.width, full.height))
                layout(region.width.coerceAtLeast(0), region.height.coerceAtLeast(0)) {
                    full.place(-region.left, -region.top)
                }
            }
        },
    ) { measurables, constraints ->
        val viewport = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) { viewport.place(clip.left, clip.top) }
    }
}

/**
 * Local Compose pixel offsets, snapped inward to the AWT grid before SwingPanel rounds its native
 * group outward. [rootOrigin] accounts for the root-to-window translation: SwingPanel rounds root
 * coordinates, while the host viewport is expressed in window coordinates.
 */
internal fun documentViewportBounds(
    bounds: Rect,
    visibleBounds: Rect,
    density: Float,
    rootOrigin: Offset = Offset.Zero,
): IntRect {
    val visible = bounds.intersect(visibleBounds)
    if (visible.isEmpty) return IntRect.Zero
    val rootBounds = bounds.translate(-rootOrigin)
    val rootVisible = visible.translate(-rootOrigin)
    val left = ceil(ceil(rootVisible.left / density) * density - rootBounds.left).toInt()
    val top = ceil(ceil(rootVisible.top / density) * density - rootBounds.top).toInt()
    val right = floor(floor(rootVisible.right / density) * density - rootBounds.left).toInt().coerceAtLeast(left)
    val bottom = floor(floor(rootVisible.bottom / density) * density - rootBounds.top).toInt().coerceAtLeast(top)
    return IntRect(left, top, right, bottom)
}
