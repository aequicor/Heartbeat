package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DocumentViewportPanelTest {
    @Test
    fun `window intersection becomes an inward rounded local clip on high density displays`() {
        assertEquals(
            IntRect(11, 21, 190, 240),
            documentViewportBounds(Rect(10f, -20f, 410f, 580f), Rect(30.2f, 20.2f, 390.8f, 460.8f), 2f),
        )
        assertEquals(
            IntRect.Zero,
            documentViewportBounds(Rect(10f, 20f, 410f, 620f), Rect(500f, 0f, 800f, 700f), 2f),
        )
        assertEquals(
            IntRect(0, 0, 200, 300),
            documentViewportBounds(Rect(10f, 20f, 410f, 620f), Rect(0f, 0f, 800f, 700f), 2f),
        )
        assertEquals(
            IntRect(2, 2, 9, 9),
            documentViewportBounds(Rect(0.8f, 0.8f, 10.8f, 10.8f), Rect(1.1f, 1.1f, 9.9f, 9.9f), 1f),
        )
    }

    @Test
    fun `partial scrolling clips both axes while retaining the full backing document and its origin`() = onEdt {
        val document = JPanel(null)
        val panel = DocumentViewportPanel(document)
        panel.setBounds(0, 0, 400, 600)
        panel.clipTo(IntRect(20, 100, 360, 450))
        val viewport = document.parent
        assertEquals(Rectangle(20, 100, 340, 350), viewport.bounds)
        assertEquals(Rectangle(-20, -100, 400, 600), document.bounds)
        // This is the exact Swing visibility API JCEF uses to clip its native window.
        assertEquals(Rectangle(20, 100, 340, 350), document.visibleRect)

        panel.clipTo(IntRect(0, 0, 400, 520))
        assertSame(viewport, document.parent)
        assertEquals(Rectangle(0, 0, 400, 600), document.bounds)
        assertEquals(Rectangle(0, 0, 400, 520), document.visibleRect)

        panel.clipTo(IntRect.Zero)
        assertEquals(Rectangle(0, 0, 400, 600), document.bounds)
        assertEquals(Rectangle(), document.visibleRect)
        panel.clipTo(IntRect(0, 0, 400, 600))
        assertSame(document, (panel.getComponent(0) as JPanel).getComponent(0))
        assertEquals(Rectangle(0, 0, 400, 600), document.visibleRect)
    }

    @Test
    fun `native resize clamps stale clips until new geometry arrives without resizing to the clip`() = onEdt {
        val document = JPanel(null)
        val panel = DocumentViewportPanel(document)
        panel.setBounds(0, 0, 400, 600)
        panel.clipTo(IntRect(20, 100, 360, 450))
        panel.setBounds(0, 0, 300, 400)
        panel.doLayout()
        assertEquals(Rectangle(-20, -100, 300, 400), document.bounds)
        assertEquals(Rectangle(20, 100, 280, 300), document.visibleRect)
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
}
