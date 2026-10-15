package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/**
 * Compose punches a rectangular hole for a heavyweight Swing view, including its surrounding surface at the corners.
 * The native page's mask exposes that hole. Restore only the missing backdrop outside the rounded page; the page
 * itself and its inner spacing remain untouched. The host supplies its surface color or the owning dialog's scrim.
 */
internal class DesktopDocumentHost : JPanel(BorderLayout()) {
    private var radius = 0.0
    private var scrim: Color? = null

    fun updateBackdrop(cornerRadius: Double, color: Color) {
        if (radius == cornerRadius && scrim == color) return
        radius = cornerRadius
        scrim = color
        repaint()
    }

    fun clearBackdrop() {
        radius = 0.0
        scrim = null
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val color = scrim ?: return
        if (radius <= 0 || graphics !is Graphics2D) return
        val outside = Area(Rectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble()))
        outside.subtract(
            Area(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), radius * 2, radius * 2)),
        )
        val corners = graphics.create() as Graphics2D
        try {
            corners.composite = AlphaComposite.Src
            corners.color = color
            corners.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            corners.fill(outside)
        } finally {
            corners.dispose()
        }
    }
}
