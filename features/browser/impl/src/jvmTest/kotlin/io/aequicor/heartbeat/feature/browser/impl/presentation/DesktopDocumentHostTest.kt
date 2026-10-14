package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDocumentHostTest {
    @Test
    fun `modal restores only the missing scrim with original alpha at retina scale`() = onEdt {
        val host = host()
        val scrim = Color(0, 0, 0, 80)
        host.updateBackdrop(12.0, scrim)
        val image = raster(host, scale = 2)
        assertEquals(scrim.rgb, image.getRGB(0, 0))
        assertEquals(0, image.getRGB(50, 50), "The document center must stay transparent")
        assertEquals(0, image.getRGB(50, 0), "No top padding or header may be painted")
        assertEquals(0, image.getRGB(0, 50), "No side padding may be painted")
        assertEquals(scrim.rgb, image.getRGB(99, 99))
    }

    @Test
    fun `repainting and changing theme replace the scrim without accumulating opacity`() = onEdt {
        val host = host()
        val first = Color(0, 0, 0, 80)
        val second = Color(0, 0, 0, 120)
        host.updateBackdrop(12.0, first)
        val image = raster(host)
        paint(host, image)
        assertEquals(first.rgb, image.getRGB(0, 0))
        host.updateBackdrop(12.0, second)
        paint(host, image)
        assertEquals(second.rgb, image.getRGB(0, 0))
    }

    @Test
    fun `start and released attachments reserve no corner paint`() = onEdt {
        val host = host()
        val scrim = Color(0, 0, 0, 80)
        host.updateBackdrop(12.0, scrim)
        host.updateBackdrop(0.0, scrim)
        assertEquals(0, raster(host).getRGB(0, 0))
        host.updateBackdrop(12.0, scrim)
        host.clearBackdrop()
        assertEquals(0, raster(host).getRGB(0, 0))
    }

    private fun host() = DesktopDocumentHost().apply {
        isOpaque = false
        setSize(50, 50)
    }

    private fun raster(host: DesktopDocumentHost, scale: Int = 1): BufferedImage =
        BufferedImage(host.width * scale, host.height * scale, BufferedImage.TYPE_INT_ARGB).also {
            paint(host, it, scale)
        }

    private fun paint(host: DesktopDocumentHost, image: BufferedImage, scale: Int = 1) {
        val graphics = image.createGraphics()
        try {
            graphics.scale(scale.toDouble(), scale.toDouble())
            host.paint(graphics)
        } finally {
            graphics.dispose()
        }
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
