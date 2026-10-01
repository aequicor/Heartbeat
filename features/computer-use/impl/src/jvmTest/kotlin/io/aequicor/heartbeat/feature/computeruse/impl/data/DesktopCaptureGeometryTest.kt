package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import io.aequicor.heartbeat.feature.computeruse.impl.domain.solidGrid
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DesktopCaptureGeometryTest {
    @Test
    fun `retina capture selects the native device variant`() {
        val logical = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        val native = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        assertSame(native, nativeResolutionImage(listOf(logical, native)))
    }

    @Test
    fun `pointer marker scales OS logical coordinates into native pixels`() {
        val source = solidGrid(400, 200, 0xFF223344.toInt())
        val marked = pointerMarker(source, ScreenBounds(100, 50, 200, 100), ScreenPoint(150, 75))
        assertEquals(-1, marked.pixel(100, 50))
        assertEquals(0xFF223344.toInt(), source.pixel(100, 50))
        assertEquals(0xFF223344.toInt(), marked.pixel(50, 25))
    }

    @Test
    fun `pointer outside the captured window leaves the frame unchanged`() {
        val source = solidGrid(400, 200, 0)
        assertSame(source, pointerMarker(source, ScreenBounds(100, 50, 200, 100), ScreenPoint(50, 25)))
    }
}
