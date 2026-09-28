package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import javax.swing.JRootPane

/** Keeps the native macOS traffic lights and titlebar dragging while extending Compose behind them. */
internal fun configureDesktopChrome(rootPane: JRootPane) {
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) return
    rootPane.putClientProperty("apple.awt.fullWindowContent", true)
    rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
    rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    rootPane.putClientProperty("apple.awt.draggableWindowBackground", true)
    Log.tag("Desktop").i { "macOS full-size content enabled; native window controls retained" }
}
