package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** A modal callback belongs to one attachment generation, including while an Escape dispatch is queued on EDT. */
internal class DesktopDocumentDismissal(
    private val dispatch: (() -> Unit) -> Unit = { action -> SwingUtilities.invokeLater(action) },
) {
    /** Identity is the attachment generation even when two generations share the same callback. */
    private class Target(private val action: () -> Unit) {
        fun dismiss() = action()
    }

    private val current = AtomicReference<Target?>()

    fun update(action: (() -> Unit)?) {
        current.set(action?.let(::Target))
    }

    /** Returns false outside a modal; stale events never acquire the replacement attachment's authority. */
    fun request(): Boolean {
        val target = current.get() ?: return false
        dispatch {
            if (current.get() === target) target.dismiss()
        }
        return true
    }
}
