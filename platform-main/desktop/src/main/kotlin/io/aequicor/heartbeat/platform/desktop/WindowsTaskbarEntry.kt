package io.aequicor.heartbeat.platform.desktop

import java.awt.EventQueue
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame

/** Balances explicit Shell AddTab/DeleteTab when Studio hides in the tray, reopens, or is disposed. */
internal class WindowsTaskbarEntry(
    private val window: JFrame,
    private val onClose: () -> Unit = {},
    private val isShowing: () -> Boolean = window::isShowing,
    private val update: (Boolean) -> Unit,
) : AutoCloseable {
    private var isRegistered = false
    private var isClosed = false
    private val components = object : ComponentAdapter() {
        override fun componentShown(event: ComponentEvent) = synchronize()
        override fun componentHidden(event: ComponentEvent) = synchronize()
    }
    private val events = object : WindowAdapter() {
        override fun windowClosed(event: WindowEvent) = close()
    }

    init {
        window.addComponentListener(components)
        window.addWindowListener(events)
        synchronize()
    }

    private fun synchronize() = EventQueue.invokeLater { change(isShowing()) }

    private fun change(visible: Boolean) {
        if (isClosed || isRegistered == visible) return
        update(visible)
        isRegistered = visible
    }

    override fun close() {
        if (isClosed) return
        try {
            change(false)
        } finally {
            isClosed = true
            window.removeComponentListener(components)
            window.removeWindowListener(events)
            onClose()
        }
    }
}
