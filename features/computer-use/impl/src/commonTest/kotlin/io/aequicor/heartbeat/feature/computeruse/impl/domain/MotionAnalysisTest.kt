package io.aequicor.heartbeat.feature.computeruse.impl.domain

import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionAnalysisTest {
    @Test
    fun `identical frames and sub-threshold flicker are no change`() {
        val frame = solidGrid(64, 48, WHITE)
        assertFalse(frame.motionSignature().compare(frame.motionSignature()).isChanged)
        val caret = frame.painted(CaptureRegion(10, 10, 1, 6), BLACK)
        assertFalse(frame.motionSignature().compare(caret.motionSignature()).isChanged, "one cell is noise")
    }

    @Test
    fun `a tall caret across a cell boundary is a sliver while a small control is not`() {
        val frame = solidGrid(128, 96, WHITE)
        val change = { region: CaptureRegion ->
            frame.motionSignature().compare(frame.painted(region, BLACK).motionSignature())
        }
        val caret = change(CaptureRegion(7, 10, 2, 36))
        assertTrue(caret.isChanged && caret.isSliver, "a 2x36 caret at 2x")
        val checkbox = change(CaptureRegion(20, 20, 14, 14))
        assertTrue(checkbox.isChanged && !checkbox.isSliver)
        val line = change(CaptureRegion(20, 20, 60, 10))
        assertTrue(line.isChanged && !line.isSliver)
    }

    @Test
    fun `frames of different sizes differ as a whole`() {
        val before = solidGrid(30, 20, WHITE)
        val after = solidGrid(31, 24, WHITE)
        val change = before.motionSignature().compare(after.motionSignature())
        assertEquals(CaptureRegion(0, 0, 30, 20), change.bounds)
        assertEquals(1.0, change.changedFraction)
    }

    @Test
    fun `a changed block is bounded by whole cells`() {
        val before = solidGrid(64, 48, WHITE)
        val after = before.painted(CaptureRegion(20, 12, 20, 10), BLACK)
        val change = before.motionSignature().compare(after.motionSignature())
        assertEquals(CaptureRegion(16, 8, 24, 16), change.bounds)
        assertTrue(change.changedFraction > 0.0)
    }

    @Test
    fun `partial edge cells stay inside the frame`() {
        val before = solidGrid(30, 20, WHITE)
        val after = before.painted(CaptureRegion(20, 10, 10, 10), BLACK)
        val bounds = before.motionSignature().compare(after.motionSignature()).bounds
        assertEquals(CaptureRegion(16, 8, 14, 12), bounds)
    }

    @Test
    fun `auto roi grows a change to a readable size inside the area`() {
        val area = CaptureRegion(0, 0, 1920, 1080)
        assertEquals(area, autoRoi(null, area))
        val small = autoRoi(CaptureRegion(100, 100, 20, 20), area)
        assertEquals(256, small.widthPx)
        assertEquals(256, small.heightPx)
        assertTrue(small.isInside(area))
        val corner = autoRoi(CaptureRegion(1900, 1060, 20, 20), area)
        assertEquals(1920, corner.right)
        assertEquals(1080, corner.bottom)
        assertEquals(area, autoRoi(CaptureRegion(0, 0, 1800, 1000), area))
    }

    @Test
    fun `auto roi respects an offset working area`() {
        val area = CaptureRegion(500, 300, 400, 300)
        val framed = autoRoi(CaptureRegion(510, 310, 10, 10), area)
        assertTrue(framed.isInside(area))
        assertEquals(500, framed.x)
    }

    @Test
    fun `union covers both rectangles`() {
        assertNull(null.union(null))
        assertEquals(
            CaptureRegion(0, 0, 30, 40),
            CaptureRegion(0, 0, 10, 10).union(CaptureRegion(20, 30, 10, 10)),
        )
    }

    private fun PixelGrid.painted(region: CaptureRegion, color: Int): PixelGrid {
        val pixels = argb.copyOf()
        for (y in region.y until region.bottom) {
            for (x in region.x until region.right) pixels[y * widthPx + x] = color
        }
        return PixelGrid(widthPx, heightPx, pixels)
    }

    private companion object {
        const val WHITE = -1
        const val BLACK = 0xFF000000.toInt()
    }
}
