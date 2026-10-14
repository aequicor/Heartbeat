package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopBrowserContainerTest {
    @Test
    fun `Mac native child paints in points without changing the parent graphics or bounds`() {
        verifyPainting(arePointCoordinatesUsed = true, expectedScale = 1.0)
    }

    @Test
    fun `other platforms retain native device scaling`() {
        verifyPainting(arePointCoordinatesUsed = false, expectedScale = 2.0)
    }

    private fun verifyPainting(arePointCoordinatesUsed: Boolean, expectedScale: Double) {
        SwingUtilities.invokeAndWait {
            var childTransform: AffineTransform? = null
            val events = mutableListOf<String>()
            val child = object : JPanel() {
                override fun paint(graphics: Graphics) {
                    childTransform = (graphics as Graphics2D).transform
                    events += "child"
                }
            }
            val container = DesktopBrowserContainer(arePointCoordinatesUsed, { events += "painted" }).apply {
                setSize(400, 600)
                add(child)
                doLayout()
            }
            assertFalse(container.isOpaque)
            val graphics = BufferedImage(900, 1400, BufferedImage.TYPE_INT_ARGB).createGraphics()
            try {
                graphics.scale(2.0, 2.0)
                graphics.translate(10.0, 20.0)
                val original = graphics.transform
                for (clip in listOf(Rectangle(0, 0, 400, 600), Rectangle(0, 0, 400, 20), Rectangle(0, 580, 400, 20))) {
                    childTransform = null
                    events.clear()
                    graphics.clip = clip
                    container.paint(graphics)
                    val received = assertNotNull(childTransform, "Visible $clip must invoke the native child")
                    assertEquals(expectedScale, received.scaleX)
                    assertEquals(expectedScale, received.scaleY)
                    assertEquals(original.translateX, received.translateX)
                    assertEquals(original.translateY, received.translateY)
                    assertEquals(original, graphics.transform)
                    assertEquals(clip, graphics.clipBounds)
                    assertEquals(if (arePointCoordinatesUsed) listOf("child", "painted") else listOf("child"), events)
                }
                assertEquals(container.size, child.size)
                assertTrue(child.parent === container)
            } finally {
                graphics.dispose()
            }
        }
    }
}
