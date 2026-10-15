package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** A CEF focus-boundary event can return only to the overlay of its still-current attachment. */
internal class DesktopDocumentFocusExit(
    private val dispatch: (() -> Unit) -> Unit = { action -> SwingUtilities.invokeLater(action) },
) {
    /** A fresh identity revokes queued events even when attachments share their callback. */
    private class Target(private val action: () -> Unit) {
        fun requestFocus() = action()
    }

    private val current = AtomicReference<Target?>()

    fun update(action: (() -> Unit)?) {
        current.set(action?.let(::Target))
    }

    fun request(): Boolean {
        val target = current.get() ?: return false
        dispatch {
            if (current.get() === target) target.requestFocus()
        }
        return true
    }
}
