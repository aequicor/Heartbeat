package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.Cursor
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDocumentCursorTest {
    @Test
    fun `native hand text and arrow cursors reach only the visible browser on EDT`() {
        val queued = mutableListOf<() -> Unit>()
        val cursor = DesktopDocumentCursor({ false }, queued::add)
        val view = VisiblePanel()
        val parent = JPanel().apply {
            this.cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
            add(view)
        }
        for (type in listOf(Cursor.HAND_CURSOR, Cursor.TEXT_CURSOR, Cursor.DEFAULT_CURSOR)) {
            assertFalse(cursor.change(view, type), "CEF must retain native cursor handling")
            SwingUtilities.invokeAndWait { queued.removeAt(0).invoke() }
            assertEquals(type, view.cursor.type)
            assertEquals(Cursor.WAIT_CURSOR, parent.cursor.type)
        }
    }

    @Test
    fun `a queued cursor cannot change a detached or released browser`() {
        val queued = mutableListOf<() -> Unit>()
        var isReleased = false
        val cursor = DesktopDocumentCursor({ isReleased }, queued::add)
        val view = VisiblePanel()
        cursor.change(view, Cursor.HAND_CURSOR)
        view.isAttached = false
        SwingUtilities.invokeAndWait { queued.removeAt(0).invoke() }
        assertFalse(view.isCursorSet)

        view.isAttached = true
        cursor.change(view, Cursor.TEXT_CURSOR)
        isReleased = true
        SwingUtilities.invokeAndWait { queued.removeAt(0).invoke() }
        assertFalse(view.isCursorSet)
    }

    @Test
    fun `unsupported cursor values leave native cursor handling and Swing inheritance intact`() {
        val queued = mutableListOf<() -> Unit>()
        val cursor = DesktopDocumentCursor({ false }, queued::add)
        val view = VisiblePanel()
        assertFalse(cursor.change(view, Cursor.CUSTOM_CURSOR))
        assertFalse(cursor.change(view, Int.MAX_VALUE))
        assertTrue(queued.isEmpty())
        assertFalse(view.isCursorSet)
    }

    private class VisiblePanel : JPanel() {
        var isAttached = true

        override fun isShowing(): Boolean = isAttached
    }
}
