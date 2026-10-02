package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.graphics.toArgb
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbMotion
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComputerUseIndicatorRasterTest {
    @Test
    fun `pulse returns to the base at token duration and reduced motion never pulses`() {
        val theme = HbColors.DesktopLight
        val motion = HbMotion()
        val base = Color(theme.computerUseShadow.toArgb(), true)
        for ((peak, duration) in listOf(
            theme.computerUseShadowStart to motion.computerUseStartMillis,
            theme.computerUseShadowInput to motion.computerUseInputMillis,
        )) {
            val color = Color(peak.toArgb(), true)
            assertEquals(color, overlayPulseColor(base, color, 0.0, duration, false))
            assertEquals(base, overlayPulseColor(base, color, duration.toDouble(), duration, false))
            assertEquals(base, overlayPulseColor(base, color, 0.0, duration, true))
            assertEquals(base, overlayPulseColor(base, color, duration * 2.0, duration, false))
        }
    }

    @Test
    fun `perimeter has a transparent center and corners without doubled alpha at every scale`() {
        for (theme in listOf(HbColors.DesktopLight, HbColors.DesktopDark)) {
            for (color in listOf(theme.computerUseShadow, theme.computerUseShadowStart, theme.computerUseShadowInput)) {
                for (scale in listOf(1, 2, 3)) {
                    val panel = PerimeterShadow(
                        Color(color.toArgb(), true),
                        HbDimensions.Desktop.computerUseShadowWidth.value.toInt(),
                    )
                    val image = render(panel, 240, 160, scale)
                    assertEquals(0, image.getRGB(120 * scale, 80 * scale).ushr(24))
                    val corner = image.getRGB(0, 0).ushr(24)
                    assertEquals(image.getRGB(120 * scale, 0).ushr(24), corner)
                    assertEquals(Color(color.toArgb(), true).alpha, corner)
                    assertTrue(image.getRGB(120 * scale, 20 * scale).ushr(24) < corner)
                }
            }
        }
    }

    @Test
    fun `agent cursor uses theme fill and contrasting outline with transparent exterior`() {
        for (theme in listOf(HbColors.DesktopLight, HbColors.DesktopDark)) {
            for (scale in listOf(1, 2, 3)) {
                val panel = AgentPointer(
                    Color(theme.computerUsePointer.toArgb(), true),
                    Color(theme.computerUsePointerOutline.toArgb(), true),
                    2f,
                )
                val image = render(panel, 24, 24, scale)
                val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width).toSet()
                assertTrue(theme.computerUsePointer.toArgb() in pixels)
                assertTrue(theme.computerUsePointerOutline.toArgb() in pixels)
                assertEquals(0, image.getRGB(image.width - 1, 0).ushr(24))
            }
        }
    }

    private fun render(panel: JPanel, width: Int, height: Int, scale: Int): BufferedImage {
        panel.setSize(width, height)
        val image = BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.scale(scale.toDouble(), scale.toDouble())
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }
}
