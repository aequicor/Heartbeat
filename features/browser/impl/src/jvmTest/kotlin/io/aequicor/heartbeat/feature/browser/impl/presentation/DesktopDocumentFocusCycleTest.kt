package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.ContainerOrderFocusTraversalPolicy
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopDocumentFocusCycleTest {
    @Test
    fun `modal Tab traversal wraps inside browser descendants without focusing the host or composer`() = onEdt {
        val window = FocusPanel().apply { isFocusCycleRoot = true }
        val host = FocusPanel()
        val composer = FocusPanel()
        val first = FocusPanel()
        val last = FocusPanel()
        window.add(host)
        window.add(composer)
        host.add(first)
        host.add(last)
        val cycle = DesktopDocumentFocusCycle(host)
        cycle.update(true)
        val policy = host.focusTraversalPolicy
        assertTrue(host.isFocusCycleRoot)
        assertSame(first, policy.getDefaultComponent(host))
        assertSame(first, policy.getComponentAfter(host, last))
        assertSame(last, policy.getComponentBefore(host, first))
        cycle.update(false)
        assertFalse(host.isFocusCycleRoot)
        assertFalse(host.isFocusTraversalPolicySet, "Start must resume its inherited traversal")
        cycle.close()
    }

    @Test
    fun `leaving a modal restores an existing explicit policy and focus root`() = onEdt {
        val original = ContainerOrderFocusTraversalPolicy()
        val host = FocusPanel().apply {
            isFocusCycleRoot = true
            focusTraversalPolicy = original
        }
        val cycle = DesktopDocumentFocusCycle(host)
        cycle.update(true)
        assertNotSame(original, host.focusTraversalPolicy)
        cycle.close()
        cycle.close()
        cycle.update(true)
        assertTrue(host.isFocusCycleRoot)
        assertSame(original, host.focusTraversalPolicy)
    }

    @Test
    fun `closing an old modal attachment cannot change the replacement focus cycle`() = onEdt {
        val oldHost = FocusPanel()
        val newHost = FocusPanel()
        val browser = FocusPanel()
        oldHost.add(browser)
        val oldCycle = DesktopDocumentFocusCycle(oldHost)
        val newCycle = DesktopDocumentFocusCycle(newHost)
        oldCycle.update(true)
        newHost.add(browser)
        newCycle.update(true)
        oldCycle.close()
        assertFalse(oldHost.isFocusCycleRoot)
        assertTrue(newHost.isFocusCycleRoot)
        assertSame(browser, newHost.focusTraversalPolicy.getComponentAfter(newHost, browser))
        newCycle.close()
    }

    private class FocusPanel : JPanel() {
        init {
            isFocusable = true
        }

        override fun isDisplayable(): Boolean = true
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
