package io.aequicor.heartbeat.ds.components

import java.awt.Component
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.SwingUtilities

/** Tracks one owner's client bounds in AWT logical screen coordinates; disposal revokes queued listener calls. */
internal class HbDialogWindowPlacement(
    private val owner: Component,
    private val client: Component,
    private val move: (Rectangle) -> Unit,
) : AutoCloseable {
    private var isInstalled = false
    private var isClosed = false
    private val listener = object : ComponentAdapter() {
        override fun componentMoved(event: ComponentEvent) = sync()
        override fun componentResized(event: ComponentEvent) = sync()
        override fun componentShown(event: ComponentEvent) = sync()
    }

    fun install() {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed || isInstalled) return
        isInstalled = true
        owner.addComponentListener(listener)
        if (client !== owner) client.addComponentListener(listener)
        sync()
    }

    private fun sync() {
        if (!isClosed && client.isShowing) move(Rectangle(client.locationOnScreen, client.size))
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (isClosed) return
        isClosed = true
        owner.removeComponentListener(listener)
        if (client !== owner) client.removeComponentListener(listener)
    }
}
