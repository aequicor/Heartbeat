package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.Component
import java.awt.ContainerOrderFocusTraversalPolicy
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Bounds JCEF's own AWT Tab traversal to the current modal attachment. CefClient moves focus using
 * an ancestor policy before notifying its focus handler, so a callback cannot prevent focus from
 * reaching the underlying composer. Start retains its ordinary traversal; disposal restores the
 * exact original policy, including inheritance, before the retained browser moves to another host.
 */
internal class DesktopDocumentFocusCycle(private val host: JComponent) : AutoCloseable {
    private val isInitiallyCycleRoot = host.isFocusCycleRoot
    private val previousPolicy = if (host.isFocusTraversalPolicySet) host.focusTraversalPolicy else null
    private val modalPolicy = object : ContainerOrderFocusTraversalPolicy() {
        override fun accept(component: Component): Boolean = component !== host && super.accept(component)
    }
    private var isModal = false
    private var isClosed = false

    fun update(value: Boolean) {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed || isModal == value) return
        isModal = value
        host.focusTraversalPolicy = if (value) modalPolicy else previousPolicy
        host.isFocusCycleRoot = value || isInitiallyCycleRoot
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed) return
        update(false)
        isClosed = true
    }
}
