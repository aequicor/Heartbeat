package io.aequicor.heartbeat.ds.layouts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Horizontal scrolling with a compact hoverable scrollbar outside the moving content. */
@Composable
public fun Modifier.hbHorizontalScroll(
    state: ScrollState,
    reverseScrolling: Boolean = false,
    thumbColor: Color = HbTheme.colors.outline,
): Modifier = hbScrollbars(state, Orientation.Horizontal, reverseScrolling, thumbColor)
    .horizontalScroll(state, reverseScrolling = reverseScrolling)

/** Vertical scrolling with a compact hoverable scrollbar outside the moving content. */
@Composable
public fun Modifier.hbVerticalScroll(state: ScrollState, reverseScrolling: Boolean = false): Modifier =
    hbScrollbars(state, Orientation.Vertical, reverseScrolling)
        .verticalScroll(state, reverseScrolling = reverseScrolling)

/** Adds only a scrollbar; use this for editors or containers which already own [state]'s scrolling. */
@Composable
public fun Modifier.hbScrollbars(
    state: ScrollState,
    orientation: Orientation = Orientation.Vertical,
    reverseScrolling: Boolean = false,
    thumbColor: Color = HbTheme.colors.outline,
): Modifier {
    val adapter = remember(state) { HbScrollStateAdapter(state) }
    return hbScrollbar(adapter, orientation, reverseScrolling, thumbColor)
}

/** Draws an index-normalized lazy scrollbar without estimating total height from measured rows. */
@Composable
public fun Modifier.hbScrollbars(
    state: LazyListState,
    orientation: Orientation = Orientation.Vertical,
    reverseScrolling: Boolean = false,
): Modifier {
    val dimensions = HbTheme.dimensions
    val itemExtent = with(LocalDensity.current) { dimensions.scrollbarLazyItemExtent.toPx() }
    val maximumExtent = dimensions.scrollbarMaxThumbFraction
    val adapter = remember(state, itemExtent, maximumExtent) {
        HbLazyScrollbarAdapter(state, itemExtent, maximumExtent)
    }
    return hbScrollbar(adapter, orientation, reverseScrolling, HbTheme.colors.outline)
}

@Composable
private fun Modifier.hbScrollbar(
    adapter: HbScrollbarAdapter,
    orientation: Orientation,
    reverseScrolling: Boolean,
    color: Color,
): Modifier {
    val dimensions = HbTheme.dimensions
    val density = LocalDensity.current
    val geometry = with(density) {
        HbScrollbarGeometry(
            dimensions.scrollbarThickness.toPx(),
            dimensions.scrollbarHoverThickness.toPx(),
            dimensions.scrollbarHoverWidth.toPx(),
            dimensions.scrollbarMinThumb.toPx(),
            dimensions.scrollbarInset.toPx(),
        )
    }
    val scope = rememberCoroutineScope()
    val interaction = remember(adapter, scope) { HbScrollbarInteraction(adapter, scope) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val isReversed = scrollbarDirection(orientation == Orientation.Horizontal, isRtl, reverseScrolling)
    val motion = HbTheme.motion
    ScrollbarVisibility(interaction, motion.scrollbarHideDelayMillis)
    val opacity = animateFloatAsState(
        targetValue = if (interaction.isVisible) 1f else 0f,
        animationSpec = if (motion.isReducedMotion) snap() else tween(motion.scrollbarFadeMillis),
        label = "scrollbarOpacity",
    )
    val style = HbScrollbarDrawing(geometry, orientation, isReversed, isRtl, color)
    return pointerInput(interaction, geometry, orientation, isReversed, isRtl) {
        val pointer = HbScrollbarPointer(interaction, geometry, orientation, isReversed, isRtl)
        try {
            awaitPointerEventScope { receiveScrollbarEvents(pointer) }
        } finally {
            interaction.isHovered = false
            interaction.finishDrag()
        }
    }.drawWithContent {
        drawContent()
        val metrics = adapter.metrics()
        if (metrics.isScrollable && opacity.value > 0f) {
            drawScrollbar(interaction, metrics, style, opacity.value)
        }
    }
}

@Composable
private fun ScrollbarVisibility(interaction: HbScrollbarInteraction, hideDelayMillis: Int) {
    LaunchedEffect(interaction, hideDelayMillis) {
        var previousPosition = interaction.adapter.positionKey
        snapshotFlow {
            ScrollbarActivity(
                interaction.adapter.positionKey,
                interaction.adapter.isScrollInProgress || interaction.isHovered || interaction.isDragging,
            )
        }.collectLatest { activity ->
            val hasMoved = activity.position != previousPosition
            previousPosition = activity.position
            if (activity.isActive || hasMoved) interaction.isVisible = true
            if (!activity.isActive) {
                delay(hideDelayMillis.toLong())
                interaction.isVisible = false
            }
        }
    }
}

private data class ScrollbarActivity(val position: Long, val isActive: Boolean)

private data class HbScrollbarDrawing(
    val geometry: HbScrollbarGeometry,
    val orientation: Orientation,
    val isReversed: Boolean,
    val isRtl: Boolean,
    val color: Color,
)

private fun DrawScope.drawScrollbar(
    interaction: HbScrollbarInteraction,
    metrics: HbScrollbarMetrics,
    style: HbScrollbarDrawing,
    opacity: Float,
) {
    val geometry = style.geometry
    val isVertical = style.orientation == Orientation.Vertical
    val thumb = scrollbarThumb(
        metrics,
        if (isVertical) size.height else size.width,
        geometry.inset,
        geometry.minimumThumb,
        style.isReversed,
        if (interaction.isThumbControlled) interaction.draggedPosition else null,
    )
    val thickness = if (interaction.isHovered || interaction.isDragging) geometry.hoverThickness else geometry.thickness
    val cross = when {
        !isVertical -> size.height - geometry.inset - thickness
        style.isRtl -> geometry.inset
        else -> size.width - geometry.inset - thickness
    }
    drawRoundRect(
        color = style.color.copy(alpha = style.color.alpha * opacity),
        topLeft = if (isVertical) Offset(cross, thumb.start) else Offset(thumb.start, cross),
        size = if (isVertical) Size(thickness, thumb.length) else Size(thumb.length, thickness),
        cornerRadius = CornerRadius(thickness / 2f),
    )
}
