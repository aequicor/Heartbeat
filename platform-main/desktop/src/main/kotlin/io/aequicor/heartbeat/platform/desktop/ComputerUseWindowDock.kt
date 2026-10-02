package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Rectangle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The session window as [ComputerUseWindowDock] sees it. Requests go through [state], which Compose applies to
 * the native window asynchronously; facts come from the native window, never from [state]: Compose copies the
 * native size and position into [state] even while the window is maximized or fullscreen.
 */
internal interface DockableWindow {
    val state: WindowState

    /** False once the native window is gone and requests can no longer be applied. */
    val isAlive: Boolean

    /** Placement the native window reports. */
    val placement: WindowPlacement

    /** Whether the native window is minimized. */
    val isMinimized: Boolean

    /** Native bounds in AWT logical pixels, which Compose treats as dp. */
    val bounds: Rectangle

    /** Usable area of the monitor holding the window, without the taskbar, Dock and menu bar. */
    val workArea: Rectangle

    /** Lets the toolkit apply requested state and deliver native window events. */
    suspend fun awaitToolkit()
}

/**
 * Pins the session window to the screen edge for an agent capture and puts it back afterwards.
 *
 * Compose resizes and moves a visible window only while it is floating, and leaving maximized or fullscreen
 * restores the floating bounds the OS remembered, discarding bounds requested together with the placement.
 * Every transition therefore takes two steps confirmed by the native window: pinning leaves maximized/fullscreen
 * first, remembers the floating bounds the window then reports and only afterwards docks it; restoring applies
 * the remembered floating bounds first and the original placement and minimized state after them. macOS leaves
 * fullscreen asynchronously, so every wait is bounded and a late window is logged and handled as it is.
 */
internal class ComputerUseWindowDock(private val window: DockableWindow) {
    private val log = Log.tag("ComputerUseWindowDock")

    // A new session must not capture bounds while the previous one is still restoring the window.
    private val transitions = Mutex()

    /** Keeps the window pinned as a [width] x [height] session until cancelled, then restores it. */
    suspend fun holdPinned(width: Int, height: Int): Nothing = transitions.withLock {
        val session = DockedSession(window.placement, window.isMinimized)
        try {
            pin(session, width, height)
            awaitCancellation()
        } finally {
            // The session ends by cancellation; restoration must still wait for the window.
            withContext(NonCancellable) { restore(session) }
        }
    }

    private suspend fun pin(session: DockedSession, width: Int, height: Int) {
        val isAlreadyFloating = session.placement == WindowPlacement.Floating && !session.isMinimized
        if (!isAlreadyFloating) log.d { "Agent capture: leaving ${session.describe()} before pinning the session" }
        requestFloating()
        awaitWindow("leave ${session.describe()}", TRANSITION_TIMEOUT, ::isFloating)
        session.floatingBounds = if (isAlreadyFloating) window.bounds else awaitSettledBounds()
        val target = computerUseSessionBounds(window.workArea, width, height)
        requestBounds(target)
        log.i { "Agent capture: session pinned to the screen edge" }
    }

    private suspend fun restore(session: DockedSession) {
        if (!window.isAlive) {
            log.d { "Agent capture ended with the window closed; nothing to restore" }
            return
        }
        val floating = session.floatingBounds
        if (floating != null) {
            restoreFloatingBounds(floating)
        } else {
            // Ended while still leaving maximized/fullscreen: requesting it again mid-transition can be lost.
            awaitWindow("finish leaving ${session.describe()}", TRANSITION_TIMEOUT, ::isFloating)
        }
        if (session.placement != WindowPlacement.Floating) {
            window.state.placement = session.placement
            log.d { "Agent capture ended: returning to ${session.placement}" }
            awaitWindow("return to ${session.placement}", TRANSITION_TIMEOUT) {
                window.placement == session.placement
            }
        }
        if (session.isMinimized) window.state.isMinimized = true
        log.i { "Agent capture ended: window restored to ${session.describe()}" }
    }

    /** Floating first: bounds requested for a maximized/fullscreen window are lost when it leaves that state. */
    private suspend fun restoreFloatingBounds(floating: Rectangle) {
        requestFloating()
        awaitWindow("return to floating", TRANSITION_TIMEOUT, ::isFloating)
        requestBounds(floating)
        awaitWindow("restore its floating bounds", BOUNDS_TIMEOUT) { window.bounds == floating }
    }

    private fun requestFloating() {
        window.state.isMinimized = false
        window.state.placement = WindowPlacement.Floating
        log.d { "Session window requested floating" }
    }

    private fun requestBounds(bounds: Rectangle) {
        window.state.size = DpSize(Dp(bounds.width.toFloat()), Dp(bounds.height.toFloat()))
        window.state.position = WindowPosition.Absolute(Dp(bounds.x.toFloat()), Dp(bounds.y.toFloat()))
        log.d { "Session window requested bounds $bounds" }
    }

    private fun isFloating(): Boolean = window.placement == WindowPlacement.Floating && !window.isMinimized

    private suspend fun awaitWindow(step: String, timeout: Duration, condition: () -> Boolean) {
        val isConfirmed = withTimeoutOrNull(timeout) {
            while (window.isAlive && !condition()) window.awaitToolkit()
            window.isAlive
        } ?: false
        if (!isConfirmed) log.w { "Session window did not $step in time; continuing from its current state" }
    }

    /** Leaving fullscreen animates on macOS: the floating bounds count once consecutive reads agree. */
    private suspend fun awaitSettledBounds(): Rectangle {
        var settled = window.bounds
        val isSettled = withTimeoutOrNull(TRANSITION_TIMEOUT) {
            var stableReads = 0
            while (window.isAlive && stableReads < STABLE_READS) {
                window.awaitToolkit()
                val current = window.bounds
                stableReads = if (current == settled) stableReads + 1 else 0
                settled = current
            }
            true
        } ?: false
        if (!isSettled) log.w { "Session window bounds kept changing; remembering the latest ones" }
        return settled
    }

    /** Original placement and, once the window has shown them, its floating bounds. */
    private class DockedSession(val placement: WindowPlacement, val isMinimized: Boolean) {
        /** Unknown while the window is still leaving maximized/fullscreen: the OS keeps them meanwhile. */
        var floatingBounds: Rectangle? = null

        fun describe(): String = if (isMinimized) "$placement (minimized)" else placement.toString()
    }

    private companion object {
        // macOS fullscreen animations take about a second; a window that is slower is handled as it is.
        val TRANSITION_TIMEOUT = 3.seconds
        val BOUNDS_TIMEOUT = 1.seconds
        const val STABLE_READS = 2
    }
}
