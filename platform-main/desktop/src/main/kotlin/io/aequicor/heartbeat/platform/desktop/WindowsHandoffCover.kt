package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import javax.swing.JFrame

private const val WS_EX_TOOLWINDOW = 0x00000080
private const val WS_EX_APPWINDOW = 0x00040000

/**
 * A preparing window renders but contributes neither visible pixels nor a second taskbar/Alt+Tab entry.
 * Reveal occurs behind the native cover after Studio is ready, and the transient style is dropped as soon as the
 * ready frame is painted: a layered window composes its whole surface through a DWM redirected surface on every
 * frame, which would cost scrolling, animation and input latency for the rest of the session.
 * All methods run on the AWT event queue; close also reveals the window on a failed handoff.
 */
internal class WindowsHandoffCover private constructor(private val window: JFrame) : AutoCloseable {
    private val user = User32.INSTANCE
    private val handle = HWND(Native.getWindowPointer(window))
    private val originalStyle = readStyle()
    private var isRevealed = false
    private var isLayeredPresentation = true
    private val isAutoFocusOriginally = window.isAutoRequestFocus

    /** True while the transient layered presentation is still applied and [unlayer] has work to do. */
    val isLayered: Boolean get() = isLayeredPresentation

    private fun hide() {
        window.isAutoRequestFocus = false
        writeStyle(
            (originalStyle and WS_EX_APPWINDOW.inv()) or WS_EX_TOOLWINDOW or WinUser.WS_EX_LAYERED,
        )
        hidePixels()
    }

    fun hidePixels() {
        // Fully transparent pixels do not intercept input while the independent render clock prepares Studio.
        // After [unlayer] the window is simply the visible surface under the native cover.
        if (!isLayeredPresentation) return
        check(user.SetLayeredWindowAttributes(handle, 0, PREPARE_ALPHA.toByte(), WinUser.LWA_ALPHA)) {
            "Cannot cover the preparing Compose window"
        }
        isRevealed = false
    }

    /** False on the first reveal: the host must paint once more before acknowledging the visible surface. */
    fun reveal(nativeHandle: Long = 0): Boolean {
        val isAlreadyRevealed = isRevealed
        if (!isRevealed && user.IsWindow(handle)) {
            if (nativeHandle != 0L) {
                check(
                    user.SetWindowPos(
                        handle,
                        HWND(Pointer(nativeHandle)),
                        0,
                        0,
                        0,
                        0,
                        WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOACTIVATE,
                    ),
                ) { "Cannot place Compose behind the native launcher" }
            }
            check(user.SetLayeredWindowAttributes(handle, 0, OPAQUE_ALPHA.toByte(), WinUser.LWA_ALPHA)) {
                "Cannot reveal the ready Compose frame"
            }
            isRevealed = true
        }
        return isAlreadyRevealed
    }

    /**
     * Drops the transient handoff style. Removing `WS_EX_LAYERED` discards DWM's redirected surface, so the host
     * paints one more frame before acknowledging the presented one; the native cover still hides the window.
     * Returns false when there is nothing left to drop.
     */
    fun unlayer(): Boolean {
        if (!isLayeredPresentation) return false
        isLayeredPresentation = false
        if (user.IsWindow(handle)) writeStyle(restoredHandoffStyle(originalStyle, isRevealed))
        return true
    }

    override fun close() {
        if (!user.IsWindow(handle)) return
        try {
            reveal()
        } finally {
            restoreStyle()
        }
    }

    private fun restoreStyle() {
        if (!user.IsWindow(handle)) return
        window.isAutoRequestFocus = isAutoFocusOriginally
        isLayeredPresentation = false
        writeStyle(restoredHandoffStyle(originalStyle, isRevealed))
        if (isRevealed) WindowsTaskbar.install(window)
    }

    private fun readStyle(): Int {
        Kernel32.INSTANCE.SetLastError(0)
        val style = user.GetWindowLong(handle, WinUser.GWL_EXSTYLE)
        check(style != 0 || Kernel32.INSTANCE.GetLastError() == 0) { "Cannot read Compose window style" }
        return style
    }

    private fun writeStyle(style: Int) {
        Kernel32.INSTANCE.SetLastError(0)
        val previous = user.SetWindowLong(handle, WinUser.GWL_EXSTYLE, style)
        check(previous != 0 || Kernel32.INSTANCE.GetLastError() == 0) { "Cannot set Compose window style" }
        check(
            user.SetWindowPos(
                handle,
                null,
                0,
                0,
                0,
                0,
                WinUser.SWP_FRAMECHANGED or WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or
                    WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE,
            ),
        ) { "Cannot apply the Compose window style" }
    }

    private fun prepare(): WindowsHandoffCover {
        var isHidden = false
        try {
            hide()
            isHidden = true
            return this
        } finally {
            if (!isHidden) restoreStyle()
        }
    }

    companion object {
        private const val OPAQUE_ALPHA = 255
        private const val PREPARE_ALPHA = 0

        fun create(window: JFrame): WindowsHandoffCover? {
            if (!System.getProperty("os.name").startsWith("Windows")) return null
            return WindowsHandoffCover(window).prepare()
        }
    }
}

/**
 * The handoff style is transient, not a property of the Studio window: a revealed window keeps a taskbar entry but
 * must never keep `WS_EX_LAYERED`, which would make DWM alpha-compose every Studio frame for the whole session.
 */
internal fun restoredHandoffStyle(originalStyle: Int, isRevealed: Boolean): Int {
    val plain = originalStyle and WinUser.WS_EX_LAYERED.inv()
    return if (isRevealed) plain or WS_EX_APPWINDOW else plain
}
