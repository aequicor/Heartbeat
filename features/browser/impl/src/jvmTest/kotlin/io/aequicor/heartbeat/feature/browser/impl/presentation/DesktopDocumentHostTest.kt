package io.aequicor.heartbeat.feature.browser.impl.presentation

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbSurfaceColors
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDocumentHostTest {
    @Test
    fun `start restores the surrounding surface only outside the rounded page in both themes`() = onEdt {
        val host = host()
        for (theme in listOf(HbSurfaceColors.DesktopLight, HbSurfaceColors.DesktopDark)) {
            val background = Color(theme.assistant.toArgb(), true)
            host.updateBackdrop(12.0, background)
            val image = raster(host, scale = 2)
            assertEquals(background.rgb, image.getRGB(0, 0))
            assertEquals(background.rgb, image.getRGB(99, 99))
            assertEquals(0, image.getRGB(50, 50), "The document center must stay transparent")
            assertEquals(0, image.getRGB(50, 0), "No top padding may be painted")
        }
    }

    @Test
    fun `modal corners restore the dimmed surface over an empty native backing in both themes`() = onEdt {
        val host = host()
        val themes = listOf(
            HbColors.DesktopLight to HbSurfaceColors.DesktopLight,
            HbColors.DesktopDark to HbSurfaceColors.DesktopDark,
        )
        for ((colors, surfaces) in themes) {
            val backdrop = Color(colors.scrim.compositeOver(surfaces.assistant).toArgb(), true)
            host.updateBackdrop(12.0, backdrop)
            val image = raster(host, scale = 2)
            assertEquals(255, image.getRGB(0, 0) ushr 24, "Corners must not depend on the cleared native backing")
            assertEquals(backdrop.rgb, image.getRGB(0, 0))
            assertEquals(0, image.getRGB(50, 50), "The document center must stay transparent")
            assertEquals(0, image.getRGB(50, 0), "No top padding or header may be painted")
            assertEquals(0, image.getRGB(0, 50), "No side padding may be painted")
            assertEquals(backdrop.rgb, image.getRGB(99, 99))
        }
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
    fun `ordinary and released attachments reserve no corner paint`() = onEdt {
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
