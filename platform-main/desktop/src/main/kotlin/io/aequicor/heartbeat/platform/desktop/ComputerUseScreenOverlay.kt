package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import io.aequicor.heartbeat.core.logging.Log
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.EventQueue
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.Window
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.swing.JDialog
import javax.swing.JPanel
import kotlin.math.roundToInt

/**
 * Shows the explicitly requested screen-control shadow, using appearance supplied by design tokens.
 * Monitor bounds and shadow width use AWT logical pixels, including on Retina displays.
 * Native mouse pass-through is configured before a window is shown; a failed configuration leaves
 * the overlay hidden rather than placing an input-blocking window over the user's computer.
 */
internal class ComputerUseScreenOverlay(private val owner: Window) : AutoCloseable {
    private val log = Log.tag("ComputerUseScreenOverlay")
    private val windows = mutableListOf<JDialog>()
    private var appearance: OverlayAppearance? = null
    private var requestedAppearance: OverlayAppearance? = null
    private var suppressionCount = 0
    private var isClosed = false
    private val nativePassThrough by lazy {
        when {
            Platform.isMac() -> MacOverlayPassThrough()
            Platform.isWindows() -> WindowsOverlayPassThrough()
            else -> error("Screen-control overlays require macOS or Windows")
        }
    }

    fun show(monitors: List<Rectangle>, color: Color, shadowWidth: Int) {
        val next = OverlayAppearance(monitors.map(::Rectangle), color, shadowWidth)
        onEventThread {
            requestedAppearance = next
            applyRequestedAppearance()
        }
    }

    fun hide() {
        onEventThread {
            requestedAppearance = null
            applyRequestedAppearance()
        }
    }

    override fun close() {
        onEventThread {
            isClosed = true
            requestedAppearance = null
            disposeWindows()
        }
    }

    /** Defers shadow updates while native windows are hidden for a screenshot, without disposing their peers. */
    fun pauseForCapture(): AutoCloseable {
        check(EventQueue.isDispatchThread()) { "Capture presentation must run on the AWT event thread" }
        suppressionCount++
        var isReleased = false
        return AutoCloseable {
            check(EventQueue.isDispatchThread()) { "Capture presentation must restore on the AWT event thread" }
            if (!isReleased) {
                isReleased = true
                suppressionCount--
                applyRequestedAppearance()
            }
        }
    }

    private fun applyRequestedAppearance() {
        if (isClosed || suppressionCount > 0) return
        val requested = requestedAppearance
        if (requested == null) {
            disposeWindows()
        } else if (requested != appearance) {
            showOnEventThread(requested)
        }
    }

    private fun showOnEventThread(next: OverlayAppearance) {
        disposeWindows()
        if (next.shadowWidth <= 0 || next.monitors.isEmpty()) return
        try {
            next.monitors.filter { it.width > 0 && it.height > 0 }.forEach { monitor ->
                val window = createWindow(monitor, next)
                windows.add(window)
                window.addNotify()
                nativePassThrough.configure(window)
                window.isVisible = true
                // AWT may adjust native styles when showing the peer. Reapply and verify them.
                nativePassThrough.configure(window)
            }
            appearance = next
            log.i { "Screen-control shadow shown on ${windows.size} displays" }
        } catch (e: CancellationException) {
            disposeWindows()
            throw e
        } catch (e: Exception) {
            log.w(e) { "Cannot configure a mouse-transparent screen-control shadow" }
            disposeWindows()
        } catch (e: LinkageError) {
            log.w(e) { "Native screen-control shadow support is unavailable" }
            disposeWindows()
        }
    }

    private fun createWindow(monitor: Rectangle, next: OverlayAppearance): JDialog = JDialog(owner).apply {
        title = "Heartbeat computer-use overlay ${UUID.randomUUID()}"
        isUndecorated = true
        isResizable = false
        type = Window.Type.POPUP
        focusableWindowState = false
        isAutoRequestFocus = false
        isAlwaysOnTop = true
        background = Color(next.color.red, next.color.green, next.color.blue, 0)
        rootPane.putClientProperty("Window.shadow", false)
        rootPane.putClientProperty("Window.hidesOnDeactivate", false)
        rootPane.putClientProperty("apple.awt.windowAccessibilityElement", false)
        contentPane = PerimeterShadow(next.color, next.shadowWidth)
        bounds = monitor
    }

    private fun disposeWindows() {
        val isShowing = windows.isNotEmpty()
        windows.forEach(Window::dispose)
        windows.clear()
        appearance = null
        if (isShowing) log.i { "Screen-control shadow hidden" }
    }

    private fun onEventThread(action: () -> Unit) {
        if (EventQueue.isDispatchThread()) action() else EventQueue.invokeLater(action)
    }
}

private data class OverlayAppearance(val monitors: List<Rectangle>, val color: Color, val shadowWidth: Int)

/** A ring-based inward fade keeps the entire middle transparent and avoids overlapping corner alpha. */
private class PerimeterShadow(private val color: Color, private val shadowWidth: Int) : JPanel() {
    init {
        isOpaque = false
        isFocusable = false
    }

    override fun paintComponent(graphics: Graphics) {
        val canvas = graphics.create() as Graphics2D
        try {
            canvas.composite = AlphaComposite.Src
            canvas.color = Color(color.red, color.green, color.blue, 0)
            canvas.fillRect(0, 0, width, height)
            val thickness = minOf(shadowWidth, width / 2, height / 2)
            repeat(thickness) { inset ->
                val fade = 1f - inset.toFloat() / thickness
                val alpha = (color.alpha * fade * fade).roundToInt()
                canvas.color = Color(color.red, color.green, color.blue, alpha)
                canvas.drawRect(inset, inset, width - inset * 2 - 1, height - inset * 2 - 1)
            }
        } finally {
            canvas.dispose()
        }
    }
}

private fun interface OverlayPassThrough {
    fun configure(window: JDialog)
}

/** Layered + transparent bypasses mouse hit testing across processes; NOACTIVATE preserves focus. */
private class WindowsOverlayPassThrough : OverlayPassThrough {
    override fun configure(window: JDialog) {
        val handle = HWND(Native.getWindowPointer(window))
        val user = User32.INSTANCE
        val previous = user.GetWindowLong(handle, WinUser.GWL_EXSTYLE)
        val styles = previous or WinUser.WS_EX_LAYERED or WinUser.WS_EX_TRANSPARENT or
            TOOL_WINDOW or NO_ACTIVATE
        Native.setLastError(0)
        val result = user.SetWindowLong(handle, WinUser.GWL_EXSTYLE, styles)
        check(result != 0 || Native.getLastError() == 0) {
            "Cannot set overlay window styles: ${Native.getLastError()}"
        }
        val flags = WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOACTIVATE or WinUser.SWP_FRAMECHANGED
        check(user.SetWindowPos(handle, HWND(Pointer.createConstant(-1)), 0, 0, 0, 0, flags)) {
            "Cannot keep overlay above other windows: ${Native.getLastError()}"
        }
        val applied = user.GetWindowLong(handle, WinUser.GWL_EXSTYLE)
        check(applied and styles == styles) { "Overlay window styles were not applied" }
    }

    private companion object {
        const val NO_ACTIVATE = 0x08000000
        const val TOOL_WINDOW = 0x00000080
    }
}

/** Cocoa work runs on its main queue, without depending on private AWT peer/native pointer layouts. */
private class MacOverlayPassThrough : OverlayPassThrough {
    private val cocoa = MacWindowAccess()

    override fun configure(window: JDialog) {
        cocoa.onMainThread {
            configureNativeWindow(cocoa.window(window.title))
        }
    }

    private fun configureNativeWindow(window: Pointer) {
        cocoa.send(window, "setIgnoresMouseEvents:", 1.toByte())
        cocoa.send(window, "setHasShadow:", 0.toByte())
        cocoa.send(window, "setHidesOnDeactivate:", 0.toByte())
        cocoa.send(window, "setLevel:", NativeLong(STATUS_LEVEL))
        cocoa.send(window, "setCollectionBehavior:", NativeLong(COLLECTION_BEHAVIOR))
        check(cocoa.boolean(window, "ignoresMouseEvents")) {
            "Native overlay still accepts mouse events"
        }
    }

    private companion object {
        // NSStatusWindowLevel keeps the screen perimeter visible above the menu bar and Dock.
        const val STATUS_LEVEL = 25L

        // CanJoinAllSpaces | Stationary | IgnoresCycle | FullScreenAuxiliary.
        const val COLLECTION_BEHAVIOR = 1L or 16L or 64L or 256L
    }
}
