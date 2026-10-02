package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MacClientGeometryTest {
    @Test
    fun `client crop maps Retina points at negative monitor origins`() {
        val pixels = PixelGrid(200, 160, IntArray(200 * 160) { it })
        val window = ScreenBounds(-300, -100, 100, 80)
        val client = ScreenBounds(-300, -80, 100, 60)
        val cropped = assertNotNull(cropClientPixels(pixels, window, client))
        assertEquals(200, cropped.widthPx)
        assertEquals(120, cropped.heightPx)
        assertEquals(pixels.pixel(0, 40), cropped.pixel(0, 0))
    }

    @Test
    fun `partial content widgets and geometry outside the window are refused`() {
        val pixels = PixelGrid(100, 80, IntArray(8000))
        val window = ScreenBounds(0, 0, 100, 80)
        assertNull(cropClientPixels(pixels, window, ScreenBounds(20, 20, 80, 60)))
        assertNull(cropClientPixels(pixels, window, ScreenBounds(0, -10, 100, 90)))
    }
}
