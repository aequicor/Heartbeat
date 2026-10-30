package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import javax.swing.JFrame

/**
 * A preparing window renders but contributes neither visible pixels nor a second taskbar/Alt+Tab entry.
 * Reveal occurs behind the native cover after Studio is ready; normal switcher style returns after its exit.
 * All methods run on the AWT event queue; close also reveals the window on a failed handoff.
 */
internal class WindowsHandoffCover private constructor(private val window: JFrame) : AutoCloseable {
    private val user = User32.INSTANCE
    private val handle = HWND(Native.getWindowPointer(window))
    private val originalStyle = readStyle()
    private val temporaryStyle = WS_EX_TOOLWINDOW or WS_EX_APPWINDOW or WinUser.WS_EX_LAYERED
    private var isRevealed = false
    private val isAutoFocusOriginally = window.isAutoRequestFocus

    private fun hide() {
        window.isAutoRequestFocus = false
        writeStyle(
            (originalStyle and WS_EX_APPWINDOW.inv()) or WS_EX_TOOLWINDOW or WinUser.WS_EX_LAYERED,
        )
        hidePixels()
    }

    fun hidePixels() {
        // Fully transparent pixels do not intercept input while the independent render clock prepares Studio.
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
        val current = readStyle()
        // Removing WS_EX_LAYERED discards DWM's redirected surface: the desktop can show for several frames
        // after the native cover is destroyed. Keep the already painted opaque surface for this HWND's lifetime.
        val restored = if (isRevealed) {
            (current and temporaryStyle.inv()) or WS_EX_APPWINDOW or WinUser.WS_EX_LAYERED
        } else {
            (current and temporaryStyle.inv()) or (originalStyle and temporaryStyle)
        }
        writeStyle(restored)
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
        private const val WS_EX_TOOLWINDOW = 0x00000080
        private const val WS_EX_APPWINDOW = 0x00040000

        fun create(window: JFrame): WindowsHandoffCover? {
            if (!System.getProperty("os.name").startsWith("Windows")) return null
            return WindowsHandoffCover(window).prepare()
        }
    }
}
