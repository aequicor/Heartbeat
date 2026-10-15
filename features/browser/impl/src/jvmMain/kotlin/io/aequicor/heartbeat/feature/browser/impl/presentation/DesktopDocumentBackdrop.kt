package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Removes only an edge modal attachment's rectangular Swing backdrops beneath its rounded native page.
 * Compose's interop group is eligible only while this host is its sole child. Its original opacity is
 * restored before changing parents or disposing the lease; unrelated Swing ancestors are never modified.
 */
internal class DesktopDocumentBackdrop(private val host: JComponent) : AutoCloseable {
    private var isRounded = false
    private var isClosed = false
    private var group: Group? = null
    private val isHostInitiallyOpaque = host.isOpaque
    private val hierarchy = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() != 0L) updateGroup()
    }

    init {
        host.addHierarchyListener(hierarchy)
    }

    fun update(hasRoundedMask: Boolean) {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed || isRounded == hasRoundedMask) return
        isRounded = hasRoundedMask
        host.isOpaque = if (isRounded) false else isHostInitiallyOpaque
        updateGroup()
        host.repaint()
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed) return
        isClosed = true
        host.removeHierarchyListener(hierarchy)
        restoreGroup()
        host.isOpaque = isHostInitiallyOpaque
    }

    private fun updateGroup() {
        check(SwingUtilities.isEventDispatchThread())
        val next = host.parent as? JComponent
        if (group?.view !== next || !isRounded) restoreGroup()
        if (!isRounded || isClosed || group != null) return
        if (next != null && next.componentCount == 1 && next.getComponent(0) === host) {
            group = Group(next, next.isOpaque)
            next.isOpaque = false
            next.repaint()
        }
    }

    private fun restoreGroup() {
        val previous = group
        group = null
        if (previous != null) {
            previous.view.isOpaque = previous.isInitiallyOpaque
            previous.view.repaint()
        }
    }

    private data class Group(val view: JComponent, val isInitiallyOpaque: Boolean)
}
