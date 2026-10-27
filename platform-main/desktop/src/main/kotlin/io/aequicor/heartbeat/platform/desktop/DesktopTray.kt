package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import java.awt.AWTException
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.util.Locale
import java.util.ResourceBundle

/** Installs an application-owned native tray icon; the returned registration removes it at application exit. */
internal fun installDesktopTray(image: Image, onOpen: () -> Unit, onQuit: () -> Unit): AutoCloseable? =
    tryInstallDesktopTray {
        if (!SystemTray.isSupported()) {
            Log.tag("DesktopTray").i { "This desktop does not support a system tray" }
            null
        } else {
            val labels = desktopTrayLabels()
            val menu = PopupMenu().apply {
                add(trayMenuItem(labels.open, onOpen))
                addSeparator()
                add(trayMenuItem(labels.quit, onQuit))
            }
            val icon = TrayIcon(image, AppChannel.current.title, menu).apply {
                isImageAutoSize = true
                addActionListener { onOpen() }
            }
            val tray = SystemTray.getSystemTray()
            tray.add(icon)
            AutoCloseable { tray.remove(icon) }
        }
    }

private fun trayMenuItem(label: String, onAction: () -> Unit): MenuItem = MenuItem(label).apply {
    addActionListener { onAction() }
}

/** An advertised tray can still reject adding an icon, for example when the desktop shell is unavailable. */
internal fun tryInstallDesktopTray(install: () -> AutoCloseable?): AutoCloseable? = try {
    install()
} catch (e: AWTException) {
    Log.tag("DesktopTray").w(e) { "Cannot install the desktop tray; window close will quit the application" }
    null
} catch (e: UnsupportedOperationException) {
    Log.tag("DesktopTray").w(e) { "Desktop tray is unavailable; window close will quit the application" }
    null
} catch (e: SecurityException) {
    Log.tag("DesktopTray").w(e) { "Desktop tray access was denied; window close will quit the application" }
    null
}

/** Native AWT menus use the desktop locale and do not participate in Compose resource composition. */
internal fun desktopTrayLabels(locale: Locale = Locale.getDefault()): DesktopTrayLabels {
    val resources = ResourceBundle.getBundle("tray", locale)
    return DesktopTrayLabels(resources.getString("open"), resources.getString("quit"))
}

internal data class DesktopTrayLabels(val open: String, val quit: String)
