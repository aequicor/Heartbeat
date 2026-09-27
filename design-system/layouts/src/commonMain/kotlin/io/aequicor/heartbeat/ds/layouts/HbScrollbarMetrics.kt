package io.aequicor.heartbeat.ds.layouts

import androidx.compose.runtime.Immutable

/** Logical position and a separately estimated extent: measurement never changes a lazy thumb's size. */
@Immutable
internal data class HbScrollbarMetrics(
    val position: Float = 0f,
    val extent: Float = 1f,
    val isScrollable: Boolean = false,
    val logicalStart: Float = 0f,
    val logicalEnd: Float = 0f,
    val logicalSize: Float = 0f,
)

internal data class HbScrollbarItem(
    val index: Int,
    val offset: Int,
    val size: Int,
    val isStickyHeader: Boolean = false,
)

internal data class HbLazyScrollbarWindow(
    val count: Int,
    val firstVisibleIndex: Int,
    val start: Int,
    val end: Int,
    val beforePadding: Int,
    val afterPadding: Int,
    val spacing: Int,
    val hasContentBefore: Boolean,
    val hasContentAfter: Boolean,
    val items: List<HbScrollbarItem>,
)

internal fun lazyScrollbarMetrics(
    window: HbLazyScrollbarWindow,
    itemExtent: Float,
    maximumExtent: Float,
): HbScrollbarMetrics {
    if (window.count == 0 || window.end <= window.start || itemExtent <= 0f) return HbScrollbarMetrics()
    val visible = window.items.filter { window.intersectsContent(it) }
    val first = visible.minByOrNull { it.index } ?: return HbScrollbarMetrics()
    val last = visible.maxByOrNull { it.index } ?: return HbScrollbarMetrics()
    val leading = window.beforePadding / itemExtent
    val gap = window.spacing / itemExtent
    val logicalSize = window.count + (window.count - 1) * gap + leading + window.afterPadding / itemExtent
    val logicalStart = itemCoordinate(window.start, first, itemExtent, leading, gap).coerceAtLeast(0f)
    val logicalEnd = itemCoordinate(window.end, last, itemExtent, leading, gap).coerceAtMost(logicalSize)
    val before = if (window.hasContentBefore) logicalStart else 0f
    val after = if (window.hasContentAfter) logicalSize - logicalEnd else 0f
    val isScrollable = window.hasContentBefore || window.hasContentAfter
    val position = if (before + after > 0f) before / (before + after) else 0f
    val estimatedExtent = (window.end - window.start) / (itemExtent * logicalSize)
    return HbScrollbarMetrics(
        position = position.coerceIn(0f, 1f),
        extent = if (isScrollable) estimatedExtent.coerceIn(0f, maximumExtent) else 1f,
        isScrollable = isScrollable,
        logicalStart = before,
        logicalEnd = logicalSize - after,
        logicalSize = logicalSize,
    )
}

private fun HbLazyScrollbarWindow.intersectsContent(item: HbScrollbarItem): Boolean {
    // A pinned item overlaps the content origin despite preceding the first ordinary item.
    // Ordinary rows wholly inside beforeContentPadding must remain part of the coordinate plane.
    val isPinned = item.index < firstVisibleIndex && (item.isStickyHeader || item.offset + item.size > 0)
    val trailingGap = if (item.index < count - 1) spacing else afterPadding
    return !isPinned && item.offset < end && item.offset + item.size + trailingGap > start
}

private fun itemCoordinate(edge: Int, item: HbScrollbarItem, itemExtent: Float, leading: Float, gap: Float): Float {
    val pixels = edge - item.offset
    val fraction = when {
        pixels < 0 -> pixels / itemExtent
        pixels <= item.size -> pixels.toFloat() / item.size.coerceAtLeast(1)
        else -> 1f + (pixels - item.size) / itemExtent
    }
    // Gaps have their own fixed logical extent, so dropping a measured neighbour cannot change them.
    return leading + item.index * (1f + gap) + fraction
}

@Immutable
internal data class HbScrollbarThumb(
    val start: Float,
    val length: Float,
    val trackStart: Float,
    val trackLength: Float,
) {
    val travel: Float get() = (trackLength - length).coerceAtLeast(0f)
}

internal fun scrollbarThumb(
    metrics: HbScrollbarMetrics,
    viewport: Float,
    inset: Float,
    minimumLength: Float,
    isReversed: Boolean,
    draggedPosition: Float? = null,
): HbScrollbarThumb {
    val track = (viewport - inset * 2f).coerceAtLeast(0f)
    val length = (track * metrics.extent).coerceIn(minimumLength.coerceAtMost(track), track)
    val logicalPosition = (draggedPosition ?: metrics.position).coerceIn(0f, 1f)
    val position = if (isReversed) 1f - logicalPosition else logicalPosition
    return HbScrollbarThumb(inset + (track - length) * position, length, inset, track)
}

internal fun scrollbarDirection(isHorizontal: Boolean, isRtl: Boolean, reverseScrolling: Boolean): Boolean =
    reverseScrolling xor (isHorizontal && isRtl)
