package io.aequicor.heartbeat.ds.layouts

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HbScrollbarMetricsTest {
    @Test
    fun emptyAndNonOverflowingListsHaveNoScrollbar() {
        assertFalse(metrics(scrollbarWindow(emptyList())).isScrollable)
        val fitting = metrics(scrollbarWindow(listOf(40, 40)))
        assertFalse(fitting.isScrollable)
        assertEquals(1f, fitting.extent)
    }

    @Test
    fun measuringVeryDifferentRowsDoesNotResizeTheThumb() {
        val heights = List(20) { if (it == 8) 960 else 24 }
        val tallRow = metrics(scrollbarWindow(heights, scroll = 192))
        val shortRows = metrics(scrollbarWindow(heights, scroll = 1152))
        assertEquals(tallRow.extent, shortRows.extent)
        assertTrue(tallRow.position < shortRows.position)
    }

    @Test
    fun largeToSmallRowBoundaryMovesContinuously() {
        val heights = List(20) { if (it == 8) 960 else 24 }
        val positions = (1150..1154).map { metrics(scrollbarWindow(heights, scroll = it)).position }
        assertContinuousForwardMotion(positions)
    }

    @Test
    fun spacingBoundaryDoesNotDependOnKeepingThePreviousMeasuredRow() {
        val window = scrollbarWindow(List(20) { 24 }, scroll = 30, spacing = 12)
        val withoutPrevious = window.copy(items = window.items.filter { it.offset + it.size > window.start })
        assertEquals(metrics(window).position, metrics(withoutPrevious).position, absoluteTolerance = 0.00001f)
        val positions = (23..37).map {
            metrics(scrollbarWindow(List(20) { 24 }, scroll = it, spacing = 12)).position
        }
        assertContinuousForwardMotion(positions)
    }

    @Test
    fun paddingPreservesContinuityWhenTheFirstVisibleIndexChanges() {
        val heights = List(20) { if (it == 8) 960 else 24 }
        val positions = (1150..1166).map {
            metrics(scrollbarWindow(heights, scroll = it, padding = 12)).position
        }
        assertContinuousForwardMotion(positions)
    }

    @Test
    fun pinnedHeaderDoesNotDistortPositionWhilePaddingRowsRemainVisible() {
        val ordinary = scrollbarWindow(List(30) { 24 }, scroll = 245, padding = 12)
        assertTrue(ordinary.items.any { it.index < ordinary.firstVisibleIndex })
        val withHeader = ordinary.copy(items = ordinary.items + HbScrollbarItem(index = 0, offset = -12, size = 32))
        assertEquals(metrics(ordinary), metrics(withHeader))
    }

    @Test
    fun pushedHeaderInsidePaddingDoesNotBecomeTheFirstOrdinaryRow() {
        val ordinary = scrollbarWindow(List(30) { 24 }, scroll = 245, padding = 12)
        for (bottom in -12..0) {
            val pushedHeader = HbScrollbarItem(index = 0, offset = bottom - 32, size = 32, isStickyHeader = true)
            assertEquals(metrics(ordinary), metrics(ordinary.copy(items = ordinary.items + pushedHeader)))
        }
    }

    @Test
    fun leadingAndTrailingPaddingReachExactEndpointsContinuously() {
        val heights = List(20) { if (it == 8) 960 else 24 }
        val maximum = heights.sum() + 19 * 12 + 24 - 240
        val beginning = (0..3).map {
            metrics(scrollbarWindow(heights, scroll = it, spacing = 12, padding = 12)).position
        }
        val ending = (maximum - 3..maximum).map {
            metrics(scrollbarWindow(heights, scroll = it, spacing = 12, padding = 12)).position
        }
        assertEquals(0f, beginning.first())
        assertEquals(1f, ending.last())
        assertContinuousForwardMotion(beginning)
        assertContinuousForwardMotion(ending)
    }

    @Test
    fun smallViewportsAndResizeKeepThumbWithinTrack() {
        val metric = HbScrollbarMetrics(position = 0.5f, extent = 0.01f, isScrollable = true)
        listOf(12f, 240f, 800f).forEach { viewport ->
            val thumb = scrollbarThumb(metric, viewport, inset = 2f, minimumLength = 28f, isReversed = false)
            assertTrue(thumb.start >= thumb.trackStart)
            assertTrue(thumb.start + thumb.length <= thumb.trackStart + thumb.trackLength)
            assertTrue(thumb.length >= minOf(28f, thumb.trackLength))
        }
    }

    @Test
    fun reverseAndRtlPlaceBothEndpointsOnTheCorrectPhysicalEdge() {
        val metric = HbScrollbarMetrics(extent = 0.2f, isScrollable = true)
        val start = scrollbarThumb(metric, 240f, 2f, 28f, isReversed = false)
        val reversedStart = scrollbarThumb(metric, 240f, 2f, 28f, isReversed = true)
        val end = scrollbarThumb(metric.copy(position = 1f), 240f, 2f, 28f, isReversed = false)
        assertEquals(start.trackStart, start.start)
        assertEquals(end.start, reversedStart.start)
        assertFalse(scrollbarDirection(isHorizontal = false, isRtl = true, reverseScrolling = false))
        assertTrue(scrollbarDirection(isHorizontal = true, isRtl = true, reverseScrolling = false))
        assertFalse(scrollbarDirection(isHorizontal = true, isRtl = true, reverseScrolling = true))
    }
}

private fun metrics(window: HbLazyScrollbarWindow): HbScrollbarMetrics = lazyScrollbarMetrics(window, 40f, 0.8f)

private fun assertContinuousForwardMotion(positions: List<Float>) {
    positions.zipWithNext().forEach { (before, after) ->
        assertTrue(after >= before, "Scrollbar moved backwards: $before -> $after")
        assertTrue(abs(after - before) < 0.005f, "One pixel created a scrollbar jump: $before -> $after")
    }
}

private fun scrollbarWindow(
    heights: List<Int>,
    scroll: Int = 0,
    spacing: Int = 0,
    padding: Int = 0,
): HbLazyScrollbarWindow {
    val viewport = 240
    var offset = -scroll
    val all = heights.mapIndexed { index, height ->
        HbScrollbarItem(index, offset, height).also { offset += height + spacing }
    }
    val maximum = (heights.sum() + (heights.size - 1).coerceAtLeast(0) * spacing + padding * 2 - viewport)
        .coerceAtLeast(0)
    return HbLazyScrollbarWindow(
        count = heights.size,
        firstVisibleIndex = all.firstOrNull { it.offset + it.size + spacing > 0 }?.index ?: 0,
        start = -padding,
        end = viewport - padding,
        beforePadding = padding,
        afterPadding = padding,
        spacing = spacing,
        hasContentBefore = scroll > 0,
        hasContentAfter = scroll < maximum,
        items = all.filter { it.offset < viewport - padding && it.offset + it.size + spacing > -padding },
    )
}
