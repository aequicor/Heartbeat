package io.aequicor.heartbeat.feature.computeruse.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComputerUseEncodingTest {

    @Test
    fun `encoding rejects an unusable quality`() {
        assertFailsWith<IllegalArgumentException> { CaptureEncoding(quality = 0) }
        assertFailsWith<IllegalArgumentException> { CaptureEncoding(quality = 101) }
    }

    @Test
    fun `encoding rejects negative limits`() {
        assertFailsWith<IllegalArgumentException> { CaptureEncoding(maxWidthPx = -1) }
        assertFailsWith<IllegalArgumentException> { CaptureEncoding(maxBytes = -8) }
    }

    @Test
    fun `lossless drops every reduction`() {
        val lossy = CaptureEncoding(
            format = CaptureFormat.Jpeg,
            quality = 40,
            colorModel = CaptureColorModel.Indexed,
            isDithered = true,
            maxWidthPx = 640,
            maxHeightPx = 480,
            maxBytes = 1024,
            isSharpened = true,
        )
        assertEquals(CapturePresets.Master, lossy.lossless())
    }

    @Test
    fun `presets stay inside their announced limits`() {
        assertEquals(CaptureFormat.Jpeg, CapturePresets.AgentOverview.format)
        assertTrue(CapturePresets.AgentOverview.maxWidthPx in 1..2048)
        assertTrue(CapturePresets.AgentOverview.maxBytes <= 300 * 1024)
        assertEquals(CaptureColorModel.Gray8, CapturePresets.AgentText.colorModel)
        assertTrue(CapturePresets.AgentText.isDithered)
        assertEquals(CaptureFormat.Png, CapturePresets.AgentDetail.format)
        assertEquals(0, CapturePresets.AgentDetail.maxWidthPx)
        assertTrue(CapturePresets.AgentDetail.maxBytes <= 2 * 1024 * 1024)
        assertEquals(CaptureFormat.Png, CapturePresets.Master.format)
        assertEquals(0, CapturePresets.Master.maxBytes)
    }

    @Test
    fun `a preset is found by its lower case name only`() {
        assertSame(CapturePresets.AgentOverview, CapturePresets.byName("OVERVIEW"))
        assertSame(CapturePresets.AgentText, CapturePresets.byName("text"))
        assertSame(CapturePresets.AgentDetail, CapturePresets.byName("detail"))
        assertSame(CapturePresets.UiPreview, CapturePresets.byName("ui"))
        assertSame(CapturePresets.Master, CapturePresets.byName("master"))
        assertNull(CapturePresets.byName("lossy"))
    }

    @Test
    fun `pixel pricing rounds the frame cost up`() {
        val cost = VisionCost.PixelsPerToken(pixelsPerToken = 750)
        assertEquals(14, cost.tokens(100, 100))
        assertEquals(800, cost.tokens(1000, 600))
        assertEquals(3072, cost.tokens(1920, 1200))
    }

    @Test
    fun `tiled pricing counts whole tiles plus the base cost`() {
        val cost = VisionCost.TiledSquare()
        assertEquals(85 + 170, cost.tokens(512, 512))
        assertEquals(85 + 4 * 170, cost.tokens(1024, 1024))
        assertEquals(85 + 4 * 170, cost.tokens(1000, 600))
    }

    @Test
    fun `a budget keeps an affordable frame at its native size`() {
        val budget = VisionBudget(maxTokens = 4096)
        assertEquals(Dimensions(1000, 800), budget.dimensionsFor(1000, 800))
    }

    @Test
    fun `a budget shrinks an expensive frame to even sides that fit`() {
        val budget = VisionBudget(maxTokens = 100)
        val fitted = budget.dimensionsFor(3840, 2160)
        assertTrue(budget.isAffordable(fitted.widthPx, fitted.heightPx))
        assertEquals(0, fitted.widthPx % 2)
        assertEquals(0, fitted.heightPx % 2)
        assertTrue(fitted.widthPx < 3840)
        val ratio = fitted.widthPx.toDouble() / fitted.heightPx.toDouble()
        assertTrue(kotlin.math.abs(ratio - 3840.0 / 2160.0) < 0.05, "aspect ratio drifted: $ratio")
    }

    @Test
    fun `a budget never produces a frame smaller than two pixels`() {
        val budget = VisionBudget(maxTokens = 1)
        val fitted = budget.dimensionsFor(4096, 4096)
        assertTrue(fitted.widthPx >= 2 && fitted.heightPx >= 2)
        assertTrue(budget.isAffordable(fitted.widthPx, fitted.heightPx))
    }

    @Test
    fun `a tiled budget shrinks below one tile boundary`() {
        val budget = VisionBudget(maxTokens = 300, cost = VisionCost.TiledSquare())
        val fitted = budget.dimensionsFor(2048, 2048)
        assertTrue(budget.isAffordable(fitted.widthPx, fitted.heightPx))
        assertTrue(fitted.widthPx <= 512)
    }

    @Test
    fun `a capture request selects either a region or a tile`() {
        assertFailsWith<IllegalArgumentException> {
            CaptureRequest(region = CaptureRegion(0, 0, 10, 10), tile = TileRef(0, 0))
        }
    }

    @Test
    fun `a crop keeps its scale inside the supported range`() {
        assertFailsWith<IllegalArgumentException> { CropRequest(CaptureId("c"), scale = 0.5) }
        assertFailsWith<IllegalArgumentException> { CropRequest(CaptureId("c"), scale = 5.0) }
        assertEquals(1.0, CropRequest(CaptureId("c")).scale)
    }

    @Test
    fun `a tile reference parses the colon form only`() {
        assertEquals(TileRef(2, 1), TileRef.parse("2:1"))
        assertEquals(TileRef(0, 0), TileRef.parse(" 0 : 0 "))
        assertNull(TileRef.parse("2"))
        assertNull(TileRef.parse("a:b"))
        assertNull(TileRef.parse("-1:0"))
        assertFailsWith<IllegalArgumentException> { TileRef(-1, 0) }
    }

    @Test
    fun `a normalized region maps to whole pixels inside the frame`() {
        val region = NormalizedRegion(0.25, 0.5, 0.5, 0.25).toRegion(1000, 800)
        assertEquals(CaptureRegion(250, 400, 500, 200), region)
    }

    @Test
    fun `a tiny normalized region still covers one pixel`() {
        val region = NormalizedRegion(0.0, 0.0, 0.0001, 0.0001).toRegion(100, 100)
        assertEquals(1, region.widthPx)
        assertEquals(1, region.heightPx)
    }

    @Test
    fun `a normalized region must stay inside the frame`() {
        assertFailsWith<IllegalArgumentException> { NormalizedRegion(0.8, 0.0, 0.4, 0.1) }
        assertFailsWith<IllegalArgumentException> { NormalizedRegion(0.0, 0.0, 0.0, 0.1) }
    }
}
