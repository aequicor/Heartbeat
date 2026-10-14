package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity

/**
 * Unobstructed window bounds, in Compose pixels, for embedded native content that cannot obey Skia
 * alpha masks. Null adds no restriction; an empty rectangle hides native content until layout.
 * Consumers intersect this with their own clipped bounds without resizing their backing document.
 * Nested hosts intersect their viewport with the inherited rectangle.
 */
public val LocalHbNativeContentViewport = compositionLocalOf<Rect?> { null }

/** Shares the hard visible region beneath the transcript's floating controls and sticky heading. */
@Composable
internal fun HbNativeContentViewport(
    modifier: Modifier = Modifier,
    overlapInsets: PaddingValues = PaddingValues(),
    pinnedHeaderBottom: Int = 0,
    content: @Composable () -> Unit,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var clippedBounds by remember { mutableStateOf(Rect.Zero) }
    val parent = LocalHbNativeContentViewport.current
    val density = LocalDensity.current
    val viewport = with(density) {
        nativeContentViewport(
            bounds,
            clippedBounds,
            parent,
            maxOf(overlapInsets.calculateTopPadding().toPx(), pinnedHeaderBottom.toFloat()),
            overlapInsets.calculateBottomPadding().toPx(),
        )
    }
    Box(
        modifier = modifier.onGloballyPositioned {
            bounds = it.boundsInWindow(clipBounds = false)
            clippedBounds = it.boundsInWindow()
        },
    ) {
        CompositionLocalProvider(LocalHbNativeContentViewport provides viewport, content = content)
    }
}

/** Insets are relative to the full host, even when an ancestor already clips part of that host. */
internal fun nativeContentViewport(
    bounds: Rect,
    clippedBounds: Rect,
    parent: Rect?,
    topInset: Float,
    bottomInset: Float,
): Rect {
    val top = bounds.top + topInset.coerceIn(0f, bounds.height)
    val bottom = (bounds.bottom - bottomInset.coerceAtLeast(0f)).coerceIn(top, bounds.bottom)
    val visible = Rect(bounds.left, top, bounds.right, bottom).intersect(clippedBounds)
    val result = parent?.let(visible::intersect) ?: visible
    return if (result.isEmpty) Rect.Zero else result
}
