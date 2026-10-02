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
import javax.swing.Timer
import kotlin.math.roundToInt

/** Colors and timing originate in design tokens; reduced motion keeps the base indication visible. */
internal data class OverlayPulse(
    val start: Color,
    val input: Color,
    val startMillis: Int,
    val inputMillis: Int,
    val isReducedMotion: Boolean,
)

/**
 * Desktop-only monitor perimeters in AWT logical coordinates (including Retina and negative origins).
 * Independent top-level peers stay visible while the session's owned window tree is hidden for pointer input.
 * Screenshot leases hide them without recreating peers; pulse ticks only repaint the existing component.
 */
internal class ComputerUseScreenOverlay : AutoCloseable {
    private val log = Log.tag("ComputerUseScreenOverlay")
    private val windows = mutableListOf<JDialog>()
    private var appearance: OverlayAppearance? = null
    private var requestedAppearance: OverlayAppearance? = null
    private var suppressionCount = 0
    private var isClosed = false
    private val nativePassThrough by lazy { overlayPassThrough() }
    private var pulseStart = 0L
    private var pulseMillis = 0
    private var pulseColor: Color? = null
    private val timer = Timer(16) { repaintPulse() }

    fun show(
        monitors: List<Rectangle>,
        color: Color,
        shadowWidth: Int,
        pulse: OverlayPulse? = null,
        session: String? = null,
    ) {
        val next = OverlayAppearance(monitors.map(::Rectangle), color, shadowWidth, pulse, session)
        onOverlayThread {
            requestedAppearance = next
            applyRequestedAppearance()
        }
    }

    fun pulseInput() = onOverlayThread {
        appearance?.pulse?.let { pulse(it.input, it.inputMillis) }
    }

    fun hide() = onOverlayThread {
        requestedAppearance = null
        applyRequestedAppearance()
    }

    override fun close() = onOverlayThread {
        isClosed = true
        requestedAppearance = null
        disposeWindows()
    }

    /** Balanced screenshot suppression; later show/hide requests are applied before restoration. */
    fun pauseForCapture(): AutoCloseable {
        check(EventQueue.isDispatchThread())
        suppressionCount++
        windows.forEach { it.isVisible = false }
        var isReleased = false
        return AutoCloseable {
            check(EventQueue.isDispatchThread())
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
        } else {
            val current = appearance
            val isSameGeometry = current != null && windows.isNotEmpty() &&
                current.monitors == requested.monitors && current.shadowWidth == requested.shadowWidth
            if (!isSameGeometry) {
                showOnEventThread(requested)
            } else {
                appearance = requested
                if (current.session != requested.session) {
                    requested.pulse?.let { pulse(it.start, it.startMillis) }
                }
                repaintPulse()
                windows.filterNot { it.isVisible }.forEach { showOverlayWindow(it, nativePassThrough) }
            }
        }
    }

    private fun pulse(color: Color, millis: Int) {
        if (appearance?.pulse?.isReducedMotion == true || millis <= 0) return
        pulseStart = System.nanoTime()
        pulseMillis = millis
        pulseColor = color
        repaintPulse()
        timer.start()
    }

    private fun repaintPulse() {
        val current = appearance ?: return
        val elapsed = (System.nanoTime() - pulseStart) / 1_000_000.0
        val isFinished = elapsed >= pulseMillis || current.pulse?.isReducedMotion == true
        val color = overlayPulseColor(
            current.color,
            pulseColor ?: current.color,
            elapsed,
            pulseMillis,
            current.pulse?.isReducedMotion == true,
        )
        windows.forEach { (it.contentPane as PerimeterShadow).setShadowColor(color) }
        if (isFinished) timer.stop()
    }

    private fun showOnEventThread(next: OverlayAppearance) {
        disposeWindows()
        if (next.shadowWidth <= 0 || next.monitors.isEmpty()) return
        try {
            next.monitors.filter { it.width > 0 && it.height > 0 }.forEach { monitor ->
                val window = newOverlayWindow("overlay").apply {
                    contentPane = PerimeterShadow(next.color, next.shadowWidth)
                    bounds = monitor
                }
                windows.add(window)
                showOverlayWindow(window, nativePassThrough)
            }
            appearance = next
            next.pulse?.let { pulse(it.start, it.startMillis) }
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

    private fun disposeWindows() {
        timer.stop()
        windows.forEach(Window::dispose)
        windows.clear()
        appearance = null
        pulseColor = null
        pulseMillis = 0
    }
}

private data class OverlayAppearance(
    val monitors: List<Rectangle>,
    val color: Color,
    val shadowWidth: Int,
    val pulse: OverlayPulse?,
    val session: String?,
)

/** Deterministic pulse envelope; the base opacity is the lower bound even after motion has ended. */
internal fun overlayPulseColor(
    base: Color,
    peak: Color,
    elapsedMillis: Double,
    durationMillis: Int,
    isReducedMotion: Boolean,
): Color {
    val remaining = if (durationMillis > 0 && !isReducedMotion) {
        (1.0 - elapsedMillis / durationMillis).coerceIn(0.0, 1.0)
    } else {
        0.0
    }
    return Color(
        base.red,
        base.green,
        base.blue,
        (base.alpha + (peak.alpha - base.alpha) * remaining).roundToInt(),
    )
}

/** A ring-based inward fade keeps the entire middle transparent and avoids overlapping corner alpha. */
internal class PerimeterShadow(private var shadowColor: Color, private val shadowWidth: Int) : JPanel() {
    init {
        isOpaque = false
        isFocusable = false
    }

    fun setShadowColor(color: Color) {
        shadowColor = color
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        val canvas = graphics.create() as Graphics2D
        try {
            canvas.composite = AlphaComposite.Src
            canvas.color = Color(0, 0, 0, 0)
            canvas.fillRect(0, 0, width, height)
            val thickness = minOf(shadowWidth, width / 2, height / 2)
            repeat(thickness) { inset ->
                val fade = 1f - inset.toFloat() / thickness
                val alpha = (shadowColor.alpha * fade * fade).roundToInt()
                canvas.color = Color(shadowColor.red, shadowColor.green, shadowColor.blue, alpha)
                canvas.drawRect(inset, inset, width - inset * 2 - 1, height - inset * 2 - 1)
            }
        } finally {
            canvas.dispose()
        }
    }
}

internal fun onOverlayThread(action: () -> Unit) {
    if (EventQueue.isDispatchThread()) action() else EventQueue.invokeLater(action)
}

internal fun newOverlayWindow(kind: String): JDialog = JDialog(null as Window?).apply {
    title = "Heartbeat computer-use $kind ${UUID.randomUUID()}"
    isUndecorated = true
    isResizable = false
    type = Window.Type.POPUP
    focusableWindowState = false
    isAutoRequestFocus = false
    isAlwaysOnTop = true
    background = Color(0, 0, 0, 0)
    rootPane.putClientProperty("Window.shadow", false)
    rootPane.putClientProperty("Window.hidesOnDeactivate", false)
    rootPane.putClientProperty("apple.awt.windowAccessibilityElement", false)
}

internal fun showOverlayWindow(window: JDialog, native: OverlayPassThrough) {
    window.addNotify()
    native.configure(window)
    window.isVisible = true
    // AWT may alter native styles during show: reapply and verify after the peer becomes visible.
    native.configure(window)
}

internal fun overlayPassThrough(): OverlayPassThrough = when {
    Platform.isMac() -> MacOverlayPassThrough()
    Platform.isWindows() -> WindowsOverlayPassThrough()
    else -> error("Computer-use indicators require macOS or Windows")
}

internal fun interface OverlayPassThrough {
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
        check(user.SetWindowPos(handle, HWND(Pointer(-1L)), 0, 0, 0, 0, flags)) {
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

/** Cocoa work runs on the AppKit main thread, without depending on private AWT peer/native pointer layouts. */
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
