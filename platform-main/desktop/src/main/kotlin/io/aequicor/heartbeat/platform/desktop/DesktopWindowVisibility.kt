package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.WindowState
import io.aequicor.heartbeat.core.logging.Log
import java.awt.EventQueue
import java.awt.Image
import java.awt.Window

/**
 * Installs the tray for the host's lifetime and exposes Stop when computer use starts in the background. The Dock
 * (macOS) and a repeated launch reaching the single [instance] (Windows taskbar, Linux launchers) open the window.
 */
@Composable
internal fun rememberDesktopWindowVisibility(
    windowState: WindowState,
    image: Image,
    isComputerUseActive: Boolean,
    instance: DesktopInstance?,
    onQuit: () -> Unit,
    startHidden: Boolean = false,
): DesktopWindowVisibility {
    val currentQuit by rememberUpdatedState(onQuit)
    val visibility = remember(windowState) { DesktopWindowVisibility(windowState, startHidden) { currentQuit() } }
    DisposableEffect(visibility, image, instance) {
        visibility.installTray { open, quit -> installDesktopTray(image, open, quit) }
        val reopen = installDesktopReopenHandler(visibility::openWindow)
        val activation = instance?.onActivate(visibility::openWindow)
        onDispose {
            try {
                activation?.close()
                reopen?.close()
            } finally {
                visibility.close()
            }
        }
    }
    DisposableEffect(visibility, isComputerUseActive) {
        if (isComputerUseActive) visibility.openWindow()
        onDispose { }
    }
    return visibility
}

/** Compose applies visibility/minimization first; focus follows on the native event queue. */
@Composable
internal fun RestoreDesktopWindowFocus(window: Window, visibility: DesktopWindowVisibility) {
    DisposableEffect(window, visibility, visibility.restoreRequest) {
        if (visibility.restoreRequest > 0) {
            EventQueue.invokeLater {
                if (visibility.isVisible && window.isDisplayable) {
                    window.toFront()
                    window.requestFocus()
                }
            }
        }
        onDispose { }
    }
}

/**
 * Keeps the window and its root alive in the tray. A close gesture may hide the window only after the native
 * tray is installed; otherwise it follows the full quit flow so the app never becomes unreachable.
 */
internal class DesktopWindowVisibility(
    private val windowState: WindowState,
    startHidden: Boolean = false,
    private val onQuit: () -> Unit,
) : AutoCloseable {
    private val log = Log.tag("DesktopWindowVisibility")
    private var tray: AutoCloseable? = null

    var isVisible: Boolean by mutableStateOf(!startHidden)
        private set

    /** Changes even when the window is already visible, so another Open gesture can bring it to the front. */
    var restoreRequest: Int by mutableIntStateOf(0)
        private set

    fun installTray(install: (onOpen: () -> Unit, onQuit: () -> Unit) -> AutoCloseable?) {
        check(tray == null) { "The desktop tray is already installed" }
        tray = install(::openWindow, onQuit)
    }

    fun closeWindow() {
        if (tray == null) {
            log.i { "No desktop tray is available; closing the application" }
            onQuit()
        } else {
            log.i { "Hiding the application window in the desktop tray" }
            isVisible = false
        }
    }

    fun openWindow() {
        log.i { "Restoring the application window" }
        windowState.isMinimized = false
        isVisible = true
        restoreRequest++
    }

    override fun close() {
        val registration = tray
        tray = null
        registration?.close()
    }
}
