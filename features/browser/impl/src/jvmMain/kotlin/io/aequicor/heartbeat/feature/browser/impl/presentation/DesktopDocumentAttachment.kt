package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.Color
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** One composition's emptyable host for a retained page; disposal never closes the page itself. */
internal class DesktopDocumentAttachment(private val panel: DesktopDocumentPanel) {
    private var host: DesktopDocumentHost? = null
    private var backdrop: DesktopDocumentBackdrop? = null
    private var isClosed = false
    private var scrim: Color? = null

    var onDismissRequest: (() -> Unit)? = null
        private set

    var cornerRadius: Double = 0.0
        private set

    /** A native corner mask belongs only to the current modal lease. */
    fun updateCornerRadius(value: Double, color: Color) {
        check(SwingUtilities.isEventDispatchThread())
        if (!isClosed) {
            cornerRadius = value
            scrim = color
            host?.updateBackdrop(value, color)
            backdrop?.update(value > 0)
            panel.updateCornerRadius(this)
        }
    }

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
        val component = DesktopDocumentHost()
        scrim?.let { component.updateBackdrop(cornerRadius, it) }
        host = component
        if (!isClosed) {
            backdrop = DesktopDocumentBackdrop(component).also { it.update(cornerRadius > 0) }
            panel.attach(this, component)
        }
        return component
    }

    fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed) return
        isClosed = true
        onDismissRequest = null
        host?.clearBackdrop()
        panel.detach(this)
        backdrop?.close()
        backdrop = null
    }
}
