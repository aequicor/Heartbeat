package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import java.awt.Component
import java.awt.Cursor
import javax.swing.SwingUtilities

/**
 * Keeps the windowed browser's Swing cursor in agreement with Chromium. JCEF forwards native mouse
 * movement into AWT, whose inherited arrow would otherwise compete with Chromium's hand or text
 * cursor. Its pinned display handler already converts CEF types to java.awt.Cursor constants.
 * Only the live, visible browser component is changed; the window and sibling controls keep their
 * own cursors. Returning false leaves CEF's native cursor handler enabled.
 */
internal class DesktopDocumentCursor(
    private val isReleased: () -> Boolean,
    private val dispatch: (() -> Unit) -> Unit = { action -> SwingUtilities.invokeLater(action) },
) {
    @HighFrequency
    fun change(view: Component, type: Int): Boolean {
        if (type in Cursor.DEFAULT_CURSOR..Cursor.MOVE_CURSOR) {
            dispatch {
                check(SwingUtilities.isEventDispatchThread())
                if (isReleased() || !view.isShowing) return@dispatch
                if (!view.isCursorSet || view.cursor.type != type) {
                    view.cursor = Cursor.getPredefinedCursor(type)
                }
            }
        }
        return false
    }
}
