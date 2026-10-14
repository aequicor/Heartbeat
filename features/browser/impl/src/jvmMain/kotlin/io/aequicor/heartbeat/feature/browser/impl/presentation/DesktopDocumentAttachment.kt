package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** One composition's emptyable host for a retained page; disposal never closes the page itself. */
internal class DesktopDocumentAttachment(private val panel: DesktopDocumentPanel) {
    private var host: JPanel? = null
    private var isClosed = false

    var onDismissRequest: (() -> Unit)? = null
        private set

    fun updateDismissRequest(callback: (() -> Unit)?) {
        check(SwingUtilities.isEventDispatchThread())
        if (!isClosed) {
            onDismissRequest = callback
            panel.updateDismissRequest(this)
        }
    }

    fun createComponent(): JComponent {
        check(SwingUtilities.isEventDispatchThread())
        host?.let { return it }
        return JPanel(BorderLayout()).also {
            host = it
            if (!isClosed) panel.attach(this, it)
        }
    }

    fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed) return
        isClosed = true
        onDismissRequest = null
        panel.detach(this)
    }
}
