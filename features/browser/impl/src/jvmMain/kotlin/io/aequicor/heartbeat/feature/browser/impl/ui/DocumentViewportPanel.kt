package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A real native clipping ancestor. The outer footprint and backing document retain their full size;
 * only the inner viewport moves and shrinks. JCEF uses this Swing hierarchy's visible rectangle to
 * clip its native peer, including the offset into a partially visible document. All access is on EDT.
 */
internal class DocumentViewportPanel(private val document: JComponent) : JPanel(null) {
    private val viewport = JPanel(null).apply { isOpaque = false }
    private var clip = IntRect.Zero

    init {
        isOpaque = false
        viewport.add(document)
        add(viewport)
    }

    fun clipTo(bounds: IntRect) {
        check(SwingUtilities.isEventDispatchThread())
        if (clip == bounds) return
        clip = bounds
        doLayout()
        revalidate()
        repaint()
    }

    override fun doLayout() {
        val visible = clip.intersect(IntRect(0, 0, width, height))
        val left = visible.left.coerceIn(0, width)
        val top = visible.top.coerceIn(0, height)
        viewport.setBounds(left, top, visible.width.coerceAtLeast(0), visible.height.coerceAtLeast(0))
        document.setBounds(-left, -top, width, height)
    }
}

/**
 * Mirrors SwingPanel's outward rounding of the full window bounds, but rounds clipped edges inward.
 * Rounding before subtracting the origin also keeps fractional positions inside the host viewport.
 */
internal fun documentViewportBounds(bounds: Rect, visibleBounds: Rect, density: Float): IntRect {
    val visible = bounds.intersect(visibleBounds)
    if (visible.isEmpty) return IntRect.Zero
    val originX = floor(bounds.left / density).toInt()
    val originY = floor(bounds.top / density).toInt()
    val left = if (visible.left == bounds.left) 0 else ceil(visible.left / density).toInt() - originX
    val top = if (visible.top == bounds.top) 0 else ceil(visible.top / density).toInt() - originY
    val rightEdge = if (visible.right == bounds.right) ceil(bounds.right / density) else floor(visible.right / density)
    val bottomEdge = if (visible.bottom == bounds.bottom) {
        ceil(bounds.bottom / density)
    } else {
        floor(visible.bottom / density)
    }
    val right = (rightEdge.toInt() - originX).coerceAtLeast(left)
    val bottom = (bottomEdge.toInt() - originY).coerceAtLeast(top)
    return IntRect(left, top, right, bottom)
}
