package io.aequicor.heartbeat.feature.browser.impl.presentation

import org.cef.OS
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import javax.swing.JPanel

/**
 * The native browser's original BorderLayout container, with a macOS compatibility paint boundary.
 * Pinned JCEF's windowed Mac component does not draw Java pixels: its paint reads Graphics2D scale
 * and sends native geometry. It multiplies only the clip by that scale, mixing Retina pixels with
 * AWT/NSView points. Supplying a unit scale keeps every native rectangle in points. Background
 * painting still uses the original graphics, and Windows/Linux retain their device-pixel contract.
 *
 * Swing's painting-origin contract routes child repaint/paintImmediately through this boundary,
 * so a later repaint cannot restore the incorrect scale. No extra container or layout is inserted.
 */
internal class DesktopBrowserContainer(
    private val arePointCoordinatesUsed: Boolean = OS.isMacintosh(),
    private val onNativePaint: () -> Unit = {},
) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
    }

    override fun isPaintingOrigin(): Boolean = arePointCoordinatesUsed

    override fun paintChildren(graphics: Graphics) {
        if (!arePointCoordinatesUsed || graphics !is Graphics2D) {
            super.paintChildren(graphics)
            return
        }
        val nativeGraphics = graphics.create() as Graphics2D
        try {
            val clip = nativeGraphics.clip
            val transform = nativeGraphics.transform
            nativeGraphics.transform = AffineTransform.getTranslateInstance(transform.translateX, transform.translateY)
            // paintChildren tests this user-space clip before calling the child. Preserve it when
            // changing the transform, otherwise a visible bottom strip can move outside the child.
            nativeGraphics.clip = clip
            super.paintChildren(nativeGraphics)
            if (componentCount > 0) onNativePaint()
        } finally {
            nativeGraphics.dispose()
        }
    }
}
