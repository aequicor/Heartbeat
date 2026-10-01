package io.aequicor.heartbeat.feature.computeruse.impl.domain

import io.aequicor.heartbeat.feature.computeruse.api.CaptureColorModel
import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.FrameSpace
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PixelGridTest {

    @Test
    fun `a region copies exactly its pixels`() {
        val grid = gradient(4, 4)
        val cropped = grid.region(CaptureRegion(1, 1, 2, 2))
        assertEquals(2, cropped.widthPx)
        assertEquals(2, cropped.heightPx)
        assertEquals(grid.pixel(1, 1), cropped.pixel(0, 0))
        assertEquals(grid.pixel(2, 2), cropped.pixel(1, 1))
    }

    @Test
    fun `a region outside the frame is refused`() {
        assertFailsWith<IllegalArgumentException> { gradient(4, 4).region(CaptureRegion(2, 2, 4, 4)) }
    }

    @Test
    fun `scaling down keeps the requested size`() {
        val scaled = gradient(40, 20).scaled(20, 10)
        assertEquals(20, scaled.widthPx)
        assertEquals(10, scaled.heightPx)
        assertEquals(200, scaled.argb.size)
    }

    @Test
    fun `scaling to the same size returns the same grid`() {
        val grid = gradient(8, 8)
        assertTrue(grid === grid.scaled(8, 8))
    }

    @Test
    fun `grayscale writes the luma into every channel`() {
        val grid = PixelGrid(1, 1, intArrayOf(0xFF000000.toInt() or (0x64 shl 16) or (0xC8 shl 8) or 0x2D))
        val gray = grid.grayscale().pixel(0, 0)
        val channel = gray and 0xFF
        assertEquals(channel, (gray shr 8) and 0xFF)
        assertEquals(channel, (gray shr 16) and 0xFF)
        // 100 * 0.299 + 200 * 0.587 + 45 * 0.114 = 152.44
        assertEquals(152, channel)
    }

    @Test
    fun `quantization snaps every channel to a level`() {
        val quantized = gradient(16, 16).quantized(2)
        quantized.argb.forEach { pixel ->
            listOf(0, 8, 16).forEach { shift ->
                val channel = (pixel shr shift) and 0xFF
                assertTrue(channel == 0 || channel == 255, "channel $channel is not binary")
            }
        }
    }

    @Test
    fun `quantization refuses a meaningless level count`() {
        assertFailsWith<IllegalArgumentException> { gradient(2, 2).quantized(1) }
        assertFailsWith<IllegalArgumentException> { gradient(2, 2).dithered(300) }
    }

    @Test
    fun `dithering produces gray pixels of the same size`() {
        val dithered = gradient(12, 8).dithered(4)
        assertEquals(12, dithered.widthPx)
        assertEquals(8, dithered.heightPx)
        dithered.argb.forEach { pixel ->
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            assertEquals(red, green)
            assertEquals(green, pixel and 0xFF)
        }
    }

    @Test
    fun `sharpening keeps size and edge pixels inside the frame`() {
        val sharpened = gradient(6, 5).sharpened()
        assertEquals(6, sharpened.widthPx)
        assertEquals(5, sharpened.heightPx)
        sharpened.argb.forEach { pixel ->
            listOf(0, 8, 16).forEach { shift ->
                val channel = (pixel shr shift) and 0xFF
                assertTrue(channel in 0..255)
            }
        }
    }

    private fun gradient(widthPx: Int, heightPx: Int): PixelGrid {
        val pixels = IntArray(widthPx * heightPx)
        for (y in 0 until heightPx) {
            for (x in 0 until widthPx) {
                val value = (x * 7 + y * 13) and 0xFF
                pixels[y * widthPx + x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
        }
        return PixelGrid(widthPx, heightPx, pixels)
    }
}

class EncodingLadderTest {

    @Test
    fun `an unlimited encoding needs no ladder`() {
        val encoding = CaptureEncoding(format = CaptureFormat.Png)
        assertEquals(listOf(encoding), EncodingLadder.candidates(encoding, 1000, 800))
    }

    @Test
    fun `a lossy ladder lowers the quality before the resolution`() {
        val encoding = CaptureEncoding(format = CaptureFormat.Jpeg, quality = 90, maxBytes = 1024)
        val candidates = EncodingLadder.candidates(encoding, 1000, 800)
        assertEquals(encoding, candidates.first())
        assertEquals(70, candidates[1].quality)
        assertEquals(MINIMUM_QUALITY, candidates[2].quality)
        assertTrue(candidates.all { it.maxBytes == 1024 })
        assertTrue(candidates.last().maxWidthPx < 1000)
        assertEquals(candidates.distinct(), candidates)
    }

    @Test
    fun `a lossless ladder drops color depth before the resolution`() {
        val encoding = CaptureEncoding(format = CaptureFormat.Png, maxBytes = 1024)
        val candidates = EncodingLadder.candidates(encoding, 1000, 800)
        assertEquals(CaptureColorModel.Rgb, candidates[0].colorModel)
        assertEquals(CaptureColorModel.Gray8, candidates[1].colorModel)
        assertEquals(CaptureColorModel.Indexed, candidates[2].colorModel)
        assertTrue(candidates[2].isDithered)
        assertTrue(candidates[3].maxWidthPx in 1 until 1000)
        assertTrue(candidates.last().maxWidthPx < candidates[3].maxWidthPx)
    }

    private companion object {
        const val MINIMUM_QUALITY = 30
    }
}

class CaptureRegionPolicyTest {

    @Test
    fun `preview size is capped by both limits and keeps the aspect ratio`() {
        assertEquals(1000 to 500, CaptureRegionPolicy.previewSize(1000, 500, 0, 0))
        assertEquals(200 to 100, CaptureRegionPolicy.previewSize(1000, 500, 200, 400))
        assertEquals(400 to 200, CaptureRegionPolicy.previewSize(1000, 500, 800, 200))
    }

    @Test
    fun `preview size stays even and at least two pixels`() {
        val (width, height) = CaptureRegionPolicy.previewSize(1001, 999, 101, 99)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
        assertTrue(width >= 2 && height >= 2)
    }

    @Test
    fun `preview coordinates scale up into the master frame`() {
        val point = CaptureRegionPolicy.toMaster(
            FramePoint(50.0, 25.0),
            FrameSpace.Preview,
            previewWidthPx = 100,
            previewHeightPx = 50,
            masterWidthPx = 1000,
            masterHeightPx = 500,
        )
        assertEquals(ScreenPoint(500, 250), point)
    }

    @Test
    fun `normalized coordinates map into master pixels`() {
        val point = CaptureRegionPolicy.toMaster(
            FramePoint(0.25, 0.5),
            FrameSpace.Normalized,
            previewWidthPx = 100,
            previewHeightPx = 50,
            masterWidthPx = 1000,
            masterHeightPx = 500,
        )
        assertEquals(ScreenPoint(250, 250), point)
    }

    @Test
    fun `a point outside the frame is refused instead of clamped`() {
        assertNull(
            CaptureRegionPolicy.toMaster(
                FramePoint(150.0, 10.0),
                FrameSpace.Preview,
                previewWidthPx = 100,
                previewHeightPx = 50,
                masterWidthPx = 1000,
                masterHeightPx = 500,
            ),
        )
    }

    @Test
    fun `master coordinates move into screen pixels with the window origin`() {
        val origin = ScreenBounds(30, 40, 1000, 500)
        val screen = CaptureRegionPolicy.toScreen(
            FramePoint(10.0, 10.0),
            FrameSpace.Master,
            previewWidthPx = 1000,
            previewHeightPx = 500,
            masterWidthPx = 1000,
            masterHeightPx = 500,
            origin = origin,
        )
        assertEquals(ScreenPoint(40, 50), screen)
    }

    @Test
    fun `a screen rectangle is clipped into master pixels`() {
        val region = CaptureRegionPolicy.screenToMaster(ScreenBounds(20, 30, 400, 200), ScreenBounds(30, 40, 1000, 500))
        assertEquals(CaptureRegion(0, 0, 400, 200), region)
    }

    @Test
    fun `clamping keeps only the shared area`() {
        assertEquals(
            CaptureRegion(10, 10, 90, 90),
            CaptureRegionPolicy.clamp(CaptureRegion(10, 10, 200, 200), CaptureRegion(0, 0, 100, 100)),
        )
        assertNull(CaptureRegionPolicy.clamp(CaptureRegion(200, 200, 10, 10), CaptureRegion(0, 0, 100, 100)))
    }
}
