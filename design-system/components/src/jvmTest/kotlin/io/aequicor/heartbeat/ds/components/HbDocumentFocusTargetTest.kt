package io.aequicor.heartbeat.ds.components

import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class HbDocumentFocusTargetTest {
    @Test
    fun `a nested renderer is selected without mistaking other focusable controls for the scene`() = onEdt {
        val root = FocusPanel()
        val group = FocusPanel().apply { isFocusable = false }
        val renderer = FocusPanel(isRenderer = true)
        root.add(FocusPanel())
        root.add(group)
        group.add(renderer)
        assertSame(renderer, root.focusTarget())
    }

    @Test
    fun `missing or multiple renderers cannot choose a content focus owner`() = onEdt {
        val root = FocusPanel()
        assertNull(root.focusTarget())
        val first = FocusPanel(isRenderer = true)
        val second = FocusPanel(isRenderer = true)
        root.add(first)
        root.add(second)
        assertNull(root.focusTarget())
        first.add(second)
        assertNull(root.focusTarget(), "A renderer must not hide another nested renderer from ambiguity checks")
    }

    @Test
    fun `hidden disabled undisplayable and unfocusable renderers do not displace the live target`() = onEdt {
        val root = FocusPanel()
        val target = FocusPanel(isRenderer = true)
        root.add(target)
        root.add(FocusPanel(isRenderer = true).apply { isVisible = false })
        root.add(FocusPanel(isRenderer = true).apply { isEnabled = false })
        root.add(FocusPanel(isRenderer = true, isTestDisplayable = false))
        root.add(FocusPanel(isRenderer = true).apply { isFocusable = false })
        assertSame(target, root.focusTarget())
    }

    @Test
    fun `a renderer inside an inaccessible branch cannot receive input`() = onEdt {
        val root = FocusPanel()
        val branch = FocusPanel()
        root.add(branch)
        branch.add(FocusPanel(isRenderer = true))
        branch.isEnabled = false
        assertNull(root.focusTarget())
        branch.isEnabled = true
        branch.isVisible = false
        assertNull(root.focusTarget())
        branch.isVisible = true
        root.isEnabled = false
        assertNull(root.focusTarget())
    }

    private fun FocusPanel.focusTarget() = documentFocusTarget(this) { it is FocusPanel && it.isRenderer }

    private class FocusPanel(val isRenderer: Boolean = false, private val isTestDisplayable: Boolean = true) :
        JPanel() {
        init {
            isFocusable = true
        }

        override fun isShowing(): Boolean = isVisible

        override fun isDisplayable(): Boolean = isTestDisplayable
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
