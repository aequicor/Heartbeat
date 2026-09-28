package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.aequicor.heartbeat.ds.layouts.hbScrollbars
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.math.roundToInt

/**
 * Separates a native sticky header's visual layer from the fading scrolling content.
 * Fill this host with the lazy list using [state], and call the supplied header content from its
 * sticky slots. [header] must apply its modifier and render the same stateless surface for each key.
 * Native measurement and placement still control height, horizontal insets and section push-off.
 * [overlapInsets] fades only the scrolling layer beneath external top/bottom panels, preserving the
 * scrollbar and pinned headings as fully opaque controls.
 */
@Composable
public fun HbStickyHeaderHost(
    state: LazyListState,
    stickyHeaderKeyPrefix: String,
    header: @Composable (key: String, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    overlapInsets: PaddingValues = PaddingValues(),
    content: @Composable (headerContent: @Composable (String) -> Unit) -> Unit,
) {
    val measurements = remember { mutableStateMapOf<String, NativeHeaderMeasurement>() }
    var hostRootX by remember { mutableFloatStateOf(0f) }
    val pinned by remember(state, stickyHeaderKeyPrefix) {
        derivedStateOf { state.pinnedHeader(stickyHeaderKeyPrefix) }
    }
    val measurement = pinned?.let { measurements[it.key] }
    val shadowOutset = HbTheme.dimensions.popupShadowRadius + HbTheme.dimensions.popupShadowOffset
    // Until the first native measurement, leave its surface visible and defer the content mask.
    val fade = if (pinned == null || measurement != null) {
        Modifier.hbLazyEdgeFades(state, stickyHeaderKeyPrefix, overlapInsets)
    } else {
        Modifier
    }
    Box(
        modifier = modifier.hbScrollbars(state).onGloballyPositioned { hostRootX = it.positionInRoot().x }
            // Desktop wheel distance depends on viewport size; use the same bounds above both layers.
            .scrollable(state, Orientation.Vertical, reverseDirection = true)
            .semantics { isTraversalGroup = true },
    ) {
        Box(modifier = Modifier.fillMaxSize().then(fade)) {
            content { key ->
                NativeStickyHeader(
                    headerKey = key,
                    isPlaceholder = pinned?.key == key && measurement != null,
                    onMeasure = { measurements[key] = it },
                    onRemove = { measurements.remove(key) },
                    header = header,
                )
            }
        }
        val placement = pinned
        if (placement != null && measurement != null) {
            val density = LocalDensity.current
            Box(modifier = Modifier.matchParentSize().clipStickyViewportTop(shadowOutset)) {
                StickyHeaderSurface(
                    headerKey = placement.key,
                    header = header,
                    modifier = Modifier.offset {
                        IntOffset((measurement.rootX - hostRootX).roundToInt(), placement.top)
                    }.requiredSize(
                        width = with(density) { measurement.size.width.toDp() },
                        height = with(density) { measurement.size.height.toDp() },
                    ).graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = (placement.top + placement.size).toFloat()
                            .div(placement.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }.semantics { traversalIndex = -1f },
                )
            }
        }
    }
}

@Composable
private fun NativeStickyHeader(
    headerKey: String,
    isPlaceholder: Boolean,
    onMeasure: (NativeHeaderMeasurement) -> Unit,
    onRemove: () -> Unit,
    header: @Composable (key: String, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnRemove by rememberUpdatedState(onRemove)
    DisposableEffect(headerKey) {
        onDispose { currentOnRemove() }
    }
    val semantics = if (isPlaceholder) Modifier.clearAndSetSemantics { } else Modifier
    Layout(
        modifier = modifier.onGloballyPositioned {
            onMeasure(NativeHeaderMeasurement(it.size, it.positionInRoot().x))
        }.then(semantics),
        content = { StickyHeaderSurface(headerKey, header) },
    ) { measurables, constraints ->
        // A section can disappear during reset before the lazy layout publishes its new keys.
        val placeable = measurables.singleOrNull()?.measure(constraints)
        layout(placeable?.width ?: 0, placeable?.height ?: 0) {
            // Keep native measurement, but leave no painted, focusable or clickable duplicate.
            if (!isPlaceholder) placeable?.placeRelative(0, 0)
        }
    }
}

@Composable
private fun StickyHeaderSurface(
    headerKey: String,
    header: @Composable (key: String, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    header(headerKey, modifier)
}

private fun Modifier.clipStickyViewportTop(shadowOutset: Dp): Modifier = drawWithCache {
    val outset = shadowOutset.toPx()
    onDrawWithContent {
        clipRect(left = -outset, top = 0f, right = size.width + outset, bottom = size.height + outset) {
            this@onDrawWithContent.drawContent()
        }
    }
}

@Immutable
private data class NativeHeaderMeasurement(val size: IntSize, val rootX: Float)

@Immutable
internal data class HbPinnedHeader(val key: String, val top: Int, val size: Int)

internal fun LazyListState.pinnedHeader(keyPrefix: String): HbPinnedHeader? {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull {
        it.key.toString().startsWith(keyPrefix) && it.offset <= 0
    } ?: return null
    return HbPinnedHeader(item.key.toString(), item.offset - info.viewportStartOffset, item.size)
}
