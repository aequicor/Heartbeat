package io.aequicor.heartbeat.feature.computeruse.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComputerUseGeometryTest {

    @Test
    fun `screen bounds expose their exclusive edges`() {
        val bounds = ScreenBounds(10, 20, 800, 600, scale = 1.5)
        assertEquals(810, bounds.right)
        assertEquals(620, bounds.bottom)
        assertEquals(CaptureRegion(10, 20, 800, 600), bounds.region())
    }

    @Test
    fun `screen bounds and regions reject an empty size`() {
        assertFailsWith<IllegalArgumentException> { ScreenBounds(0, 0, 0, 100) }
        assertFailsWith<IllegalArgumentException> { CaptureRegion(0, 0, 100, -1) }
        assertFailsWith<IllegalArgumentException> { CaptureRegion(-1, 0, 100, 100) }
    }

    @Test
    fun `a region knows whether it stays inside another one`() {
        val frame = CaptureRegion(0, 0, 1000, 800)
        assertTrue(CaptureRegion(10, 10, 990, 790).isInside(frame))
        assertFalse(CaptureRegion(900, 0, 200, 100).isInside(frame))
    }

    @Test
    fun `disjoint regions do not intersect`() {
        assertNull(CaptureRegion(0, 0, 10, 10).intersect(CaptureRegion(20, 20, 10, 10)))
        assertEquals(
            CaptureRegion(5, 5, 5, 5),
            CaptureRegion(0, 0, 10, 10).intersect(CaptureRegion(5, 5, 10, 10)),
        )
    }

    @Test
    fun `a tile grid covers the master frame with overlapping edges`() {
        val grid = TileGrid.forMaster(2048, 1024)
        assertEquals(3, grid.columns)
        assertEquals(1, grid.rows)
        assertEquals(CaptureRegion(0, 0, 1024, 1024), grid.region(0, 0, 2048, 1024))
        assertEquals(CaptureRegion(960, 0, 1024, 1024), grid.region(1, 0, 2048, 1024))
        assertEquals(CaptureRegion(1920, 0, 128, 1024), grid.region(2, 0, 2048, 1024))
        assertNull(grid.region(3, 0, 2048, 1024))
        assertNull(grid.region(0, 1, 2048, 1024))
    }

    @Test
    fun `a small frame needs a single tile`() {
        val grid = TileGrid.forMaster(800, 600)
        assertEquals(TileGrid(1, 1, 1024, 1024, 64), grid)
        assertEquals(CaptureRegion(0, 0, 800, 600), grid.region(0, 0, 800, 600))
    }

    @Test
    fun `a tile must be larger than the overlap`() {
        assertFailsWith<IllegalArgumentException> { TileGrid.forMaster(100, 100, tileWidthPx = 64) }
        assertFailsWith<IllegalArgumentException> { TileGrid(0, 1, 1024, 1024, 64) }
    }

    @Test
    fun `a crop is resolved against the master frame`() {
        val master = CaptureRef(
            id = CaptureId("m"),
            session = CaptureSessionId("s"),
            format = CaptureFormat.Png,
            widthPx = 500,
            heightPx = 400,
            region = CaptureRegion(0, 0, 1000, 800),
            masterWidthPx = 1000,
            masterHeightPx = 800,
            bytes = 10,
            estimatedTokens = 5,
            path = "/tmp/m.png",
            sequence = 1,
        )
        val inside = CropRequest(master.id, region = CaptureRegion(100, 100, 200, 200))
        assertEquals(CaptureRegion(100, 100, 200, 200), inside.regionIn(master))
        assertNull(CropRequest(master.id, CaptureRegion(900, 0, 200, 100)).regionIn(master))
        assertEquals(CaptureRegion(0, 0, 1000, 800), CropRequest(master.id).regionIn(master))
        assertEquals(
            CaptureRegion(250, 200, 500, 400),
            CropRequest(master.id, normalized = NormalizedRegion(0.25, 0.25, 0.5, 0.5)).regionIn(master),
        )
        assertEquals(0.5, master.previewScale)
    }

    @Test
    fun `capabilities decide which modes and input are allowed`() {
        val base = ComputerUseCapabilities(
            isCaptureAvailable = true,
            isWindowCaptureAvailable = true,
            isInputAvailable = true,
            isDesktopInputAllowed = false,
        )
        val window = ComputerUseMode.Window(
            WindowTarget(WindowId("w"), "app", "title", ScreenBounds(0, 0, 100, 100)),
        )
        assertTrue(base.supports(ComputerUseMode.Desktop()))
        assertTrue(base.supports(window))
        assertTrue(base.allowsInput(window))
        assertFalse(base.allowsInput(ComputerUseMode.Desktop()))
        assertFalse(base.allowsInput(null))
        assertFalse(base.copy(isWindowCaptureAvailable = false).supports(window))
        assertFalse(base.copy(isCaptureAvailable = false).supports(ComputerUseMode.Desktop()))
        assertTrue(base.copy(isDesktopInputAllowed = true).allowsInput(ComputerUseMode.Desktop()))
        assertFalse(base.copy(isInputAvailable = false).allowsInput(window))
    }

    @Test
    fun `printing a capture reference hides its path`() {
        val reference = CaptureRef(
            id = CaptureId("m"),
            session = CaptureSessionId("s"),
            format = CaptureFormat.Png,
            widthPx = 10,
            heightPx = 10,
            region = CaptureRegion(0, 0, 10, 10),
            masterWidthPx = 10,
            masterHeightPx = 10,
            bytes = 10,
            estimatedTokens = 1,
            path = "/private/tmp/secret.png",
            sequence = 1,
        )
        assertFalse(reference.toString().contains("tmp"))
        assertTrue(reference.toString().contains("CaptureRef"))
    }
}
