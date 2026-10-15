package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.desktop.AppReopenedEvent
import java.awt.desktop.AppReopenedListener
import java.util.concurrent.atomic.AtomicBoolean

/** Restores a running macOS application from its Dock icon; unsupported desktop hosts use the tray menu. */
internal fun installDesktopReopenHandler(onOpen: () -> Unit): AutoCloseable? = try {
    if (Desktop.isDesktopSupported()) {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
            DesktopReopenHandler(onOpen, desktop::removeAppEventListener).also(desktop::addAppEventListener)
        } else {
            null
        }
    } else {
        null
    }
} catch (error: UnsupportedOperationException) {
    Log.tag("DesktopReopenHandler").w(error) { "Application reopen events are unavailable" }
    null
} catch (error: SecurityException) {
    Log.tag("DesktopReopenHandler").w(error) { "Application reopen events were denied" }
    null
}

/** A queued native event must not reopen a window after the application has disposed its registration. */
internal class DesktopReopenHandler(
    private val onOpen: () -> Unit,
    private val unregister: (AppReopenedListener) -> Unit,
) : AppReopenedListener,
    AutoCloseable {
    private val isClosed = AtomicBoolean(false)

    override fun appReopened(event: AppReopenedEvent?) {
        EventQueue.invokeLater {
            if (!isClosed.get()) onOpen()
        }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) unregister(this)
    }
}
