package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDocumentBackdropTest {
    @Test
    fun `rounded native pixels have transparent corners through every owned Swing backdrop`() = onEdt {
        val group = JPanel(BorderLayout())
        val host = JPanel(BorderLayout())
        val nativePixels = object : JComponent() {
            override fun paintComponent(graphics: Graphics) {
                graphics.color = Color.RED
                graphics.fillRoundRect(0, 0, width, height, 40, 40)
            }
        }
        host.add(nativePixels)
        val backdrop = DesktopDocumentBackdrop(host)
        backdrop.update(true)
        group.add(host)
        group.setSize(100, 100)
        group.doLayout()
        host.doLayout()
        val modal = raster(group)
        assertEquals(0, modal.getRGB(0, 0), "A rectangular Swing background filled the masked corner")
        assertEquals(Color.RED.rgb, modal.getRGB(50, 50))
        assertFalse(host.isOpaque)
        assertFalse(group.isOpaque)
        backdrop.update(false)
        assertEquals(255, raster(group).getRGB(0, 0) ushr 24, "Start must retain its ordinary opaque backdrop")
        assertTrue(host.isOpaque)
        assertTrue(group.isOpaque)
        backdrop.close()
    }

    @Test
    fun `moving or closing a lease restores only the previous sole-child interop parent`() = onEdt {
        val first = JPanel()
        val second = JPanel().apply { isOpaque = false }
        val host = JPanel()
        val backdrop = DesktopDocumentBackdrop(host)
        first.add(host)
        backdrop.update(true)
        assertFalse(first.isOpaque)
        first.remove(host)
        assertTrue(first.isOpaque)
        second.add(host)
        backdrop.close()
        backdrop.close()
        backdrop.update(true)
        assertFalse(second.isOpaque, "Prior transparency must be preserved")
        assertTrue(host.isOpaque)
    }

    @Test
    fun `a shared parent is never modified`() = onEdt {
        val shared = JPanel()
        shared.add(JPanel())
        val host = JPanel()
        shared.add(host)
        val backdrop = DesktopDocumentBackdrop(host)
        backdrop.update(true)
        assertTrue(shared.isOpaque)
        backdrop.close()
    }

    private fun raster(view: JComponent): BufferedImage {
        val result = BufferedImage(view.width, view.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try {
            view.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return result
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
