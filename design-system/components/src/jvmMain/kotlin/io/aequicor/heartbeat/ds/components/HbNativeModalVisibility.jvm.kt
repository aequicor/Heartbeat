package io.aequicor.heartbeat.ds.components

import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.SwingUtilities

/**
 * Window-local visibility for native peers which cannot be dimmed or blocked by a Compose overlay.
 * Attach to the native peer's Swing container. Hiding keeps its DOM alive; the original visibility returns
 * after the modal closes or the container leaves its root. No other window or component is mutated.
 */
public class HbNativeModalVisibility(private val component: JComponent) {
    private var root: JRootPane? = null
    private var owner: HbNativeModalOwner? = null
    private var isPreviouslyVisible: Boolean? = null
    private var isAttached = false
    private val changes = PropertyChangeListener { sync() }
    private val hierarchy = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() != 0L) followRoot()
    }

    /** Starts observing only the current root; safe to call again after Swing reparents the retained page. */
    public fun attach() {
        check(SwingUtilities.isEventDispatchThread())
        if (isAttached) return
        isAttached = true
        component.addHierarchyListener(hierarchy)
        followRoot()
    }

    /** Stops observation before removal, restoring only a visibility value this lease changed. */
    public fun detach() {
        check(SwingUtilities.isEventDispatchThread())
        isAttached = false
        component.removeHierarchyListener(hierarchy)
        root?.removePropertyChangeListener(HbNativeModalOwner.PROPERTY, changes)
        root = null
        restore()
    }

    /** Assigns the composition's modal lease; null means ordinary background content. */
    public fun updateOwner(value: HbNativeModalOwner?) {
        check(SwingUtilities.isEventDispatchThread())
        owner = value
        sync()
    }

    private fun followRoot() {
        val next = SwingUtilities.getRootPane(component)
        if (root !== next) {
            root?.removePropertyChangeListener(HbNativeModalOwner.PROPERTY, changes)
            restore()
            root = next
            next?.addPropertyChangeListener(HbNativeModalOwner.PROPERTY, changes)
        }
        sync()
    }

    private fun sync() {
        val active = root?.getClientProperty(HbNativeModalOwner.PROPERTY)
        if (active != null && active !== owner) {
            if (isPreviouslyVisible == null) isPreviouslyVisible = component.isVisible
            component.isVisible = false
        } else {
            restore()
        }
    }

    private fun restore() {
        isPreviouslyVisible?.let { component.isVisible = it }
        isPreviouslyVisible = null
    }
}
