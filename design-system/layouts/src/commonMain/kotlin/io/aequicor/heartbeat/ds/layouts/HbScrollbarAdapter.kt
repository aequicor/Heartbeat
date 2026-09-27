package io.aequicor.heartbeat.ds.layouts

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private const val SEEK_PASS_LIMIT = 18
private const val SEEK_POSITION_TOLERANCE = 0.0005f

internal interface HbScrollbarAdapter {
    val isScrollInProgress: Boolean
    val positionKey: Long
    fun metrics(): HbScrollbarMetrics
    suspend fun seek(position: Float)
}

internal class HbScrollStateAdapter(private val state: ScrollState) : HbScrollbarAdapter {
    override val isScrollInProgress: Boolean get() = state.isScrollInProgress
    override val positionKey: Long get() = state.value.toLong()

    override fun metrics(): HbScrollbarMetrics {
        val maximum = state.maxValue
        if (maximum <= 0 || maximum == Int.MAX_VALUE || state.viewportSize <= 0) return HbScrollbarMetrics()
        return HbScrollbarMetrics(
            position = (state.value.toFloat() / maximum).coerceIn(0f, 1f),
            extent = state.viewportSize.toFloat() / (maximum.toFloat() + state.viewportSize),
            isScrollable = true,
        )
    }

    override suspend fun seek(position: Float) {
        if (!metrics().isScrollable) return
        state.scrollTo((position.coerceIn(0f, 1f) * state.maxValue).roundToInt())
    }
}

internal class HbLazyScrollbarAdapter(
    private val state: LazyListState,
    private val itemExtent: Float,
    private val maximumExtent: Float,
) : HbScrollbarAdapter {
    override val isScrollInProgress: Boolean get() = state.isScrollInProgress
    override val positionKey: Long
        get() = state.firstVisibleItemIndex.toLong().shl(Int.SIZE_BITS) or state.firstVisibleItemScrollOffset.toLong()

    override fun metrics(): HbScrollbarMetrics {
        val info = state.layoutInfo
        return lazyScrollbarMetrics(
            HbLazyScrollbarWindow(
                count = info.totalItemsCount,
                firstVisibleIndex = state.firstVisibleItemIndex,
                start = info.viewportStartOffset,
                end = info.viewportEndOffset,
                beforePadding = info.beforeContentPadding,
                afterPadding = info.afterContentPadding,
                spacing = info.mainAxisItemSpacing,
                hasContentBefore = state.canScrollBackward,
                hasContentAfter = state.canScrollForward,
                items = info.visibleItemsInfo.map {
                    HbScrollbarItem(
                        it.index,
                        it.offset,
                        it.size,
                        isStickyHeader = it.contentType == "hb-section-header",
                    )
                },
            ),
            itemExtent,
            maximumExtent,
        )
    }

    override suspend fun seek(position: Float) {
        val count = state.layoutInfo.totalItemsCount
        if (count == 0) return
        val target = position.coerceIn(0f, 1f)
        when (target) {
            0f -> state.scrollToItem(0)
            1f -> seekEnd(count - 1)
            else -> seekWithin(target)
        }
    }

    private suspend fun seekWithin(position: Float) {
        val initial = metrics()
        var lower = 0f
        var upper = initial.logicalSize
        var target = position * (initial.logicalSize - initial.logicalEnd + initial.logicalStart)
        // Measured destination viewports make the mapping nonlinear. A bounded binary search
        // converges without retaining measurements or scanning any uncomposed history.
        repeat(SEEK_PASS_LIMIT) {
            seekLogicalStart(target)
            val actual = metrics()
            if (abs(actual.position - position) <= SEEK_POSITION_TOLERANCE) return
            if (actual.position < position) lower = target else upper = target
            target = (lower + upper) / 2f
        }
    }

    private suspend fun seekLogicalStart(coordinate: Float) {
        val info = state.layoutInfo
        if (info.totalItemsCount == 0) return
        val leading = info.beforeContentPadding / itemExtent
        val step = 1f + info.mainAxisItemSpacing / itemExtent
        val target = coordinate - leading
        val index = floor(target / step).toInt().coerceIn(0, info.totalItemsCount - 1)
        val fraction = target - index * step
        state.scrollToItem(index)
        val measured = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        val pixels = when {
            fraction < 0f -> fraction * itemExtent
            fraction <= 1f -> fraction * measured.size
            else -> measured.size + (fraction - 1f) * itemExtent
        }
        // LazyList's scroll offset is relative to content origin; metrics start at the padded viewport.
        state.scrollToItem(index, (pixels + info.beforeContentPadding).roundToInt())
    }

    private suspend fun seekEnd(index: Int) {
        state.scrollToItem(index)
        val info = state.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull { it.index == index }
        state.scrollBy(((last?.size ?: 0) + info.afterContentPadding).toFloat())
    }
}
