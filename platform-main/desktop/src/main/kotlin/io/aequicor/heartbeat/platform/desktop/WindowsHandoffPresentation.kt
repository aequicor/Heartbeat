package io.aequicor.heartbeat.platform.desktop

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.awt.Frame
import javax.swing.JFrame

/** Physical DWM frame bounds account for JBR's invisible resize border and mixed monitor DPI. */
internal object WindowsHandoffPresentation {
    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val dwm by lazy { NativeLibrary.getInstance("dwmapi") }
    private const val EXTENDED_FRAME_BOUNDS = 9

    fun isNativeAlive(presentation: HandoffPresentation): Boolean {
        if (!isWindows) return true
        val handle = HWND(Pointer(presentation.nativeHandle))
        val process = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(handle, process)
        return User32.INSTANCE.IsWindow(handle) && process.value == presentation.nativePid
    }

    fun apply(window: JFrame, presentation: HandoffPresentation) {
        if (!isWindows) return
        if (presentation.isMinimized || presentation.isMaximized) {
            window.extendedState = Frame.NORMAL
            align(
                window,
                presentation.copy(bounds = presentation.normalBounds, isMinimized = false, isMaximized = false),
            )
        }
        window.extendedState = when {
            presentation.isMinimized -> Frame.ICONIFIED or if (presentation.isMaximized) Frame.MAXIMIZED_BOTH else 0
            presentation.isMaximized -> Frame.MAXIMIZED_BOTH
            else -> Frame.NORMAL
        }
        align(window, presentation)
    }

    /** False means geometry changed: another paint must finish before revealing Compose. */
    fun align(window: JFrame, presentation: HandoffPresentation): Boolean {
        if (!isWindows) return true
        val expectedState = if (presentation.isMaximized) Frame.MAXIMIZED_BOTH else Frame.NORMAL
        return when {
            presentation.isMinimized -> false

            window.extendedState != expectedState -> {
                window.extendedState = expectedState
                false
            }

            else -> alignBounds(window, presentation)
        }
    }

    private fun alignBounds(window: JFrame, presentation: HandoffPresentation): Boolean {
        val handle = HWND(Native.getWindowPointer(window))
        val outer = RECT()
        check(User32.INSTANCE.GetWindowRect(handle, outer)) { "Cannot read Compose window bounds" }
        val visible = RECT()
        val result = dwm.getFunction("DwmGetWindowAttribute", Function.ALT_CONVENTION).invokeInt(
            arrayOf(handle, EXTENDED_FRAME_BOUNDS, visible.pointer, visible.size()),
        )
        visible.read()
        val frame = if (result == 0 && visible.right > visible.left) visible else outer
        val target = presentation.bounds
        val actual = HandoffBounds(frame.left, frame.top, frame.right - frame.left, frame.bottom - frame.top)
        return if (actual == target) {
            true
        } else {
            if (!presentation.isMaximized) resize(window, target, outer, frame)
            false
        }
    }

    private fun resize(window: JFrame, target: HandoffBounds, outer: RECT, frame: RECT) {
        check(
            User32.INSTANCE.SetWindowPos(
                HWND(Native.getWindowPointer(window)),
                null,
                target.x + outer.left - frame.left,
                target.y + outer.top - frame.top,
                target.width + outer.right - outer.left - (frame.right - frame.left),
                target.height + outer.bottom - outer.top - (frame.bottom - frame.top),
                WinUser.SWP_NOACTIVATE or WinUser.SWP_NOZORDER,
            ),
        ) { "Cannot apply native launcher bounds" }
    }

    fun flush() {
        if (isWindows) {
            check(dwm.getFunction("DwmFlush", Function.ALT_CONVENTION).invokeInt(emptyArray()) == 0) {
                "Cannot flush the presented desktop frame"
            }
        }
    }
}
