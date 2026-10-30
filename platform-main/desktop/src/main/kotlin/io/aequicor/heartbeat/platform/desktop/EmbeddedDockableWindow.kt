package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.Toolkit

/** Applies docking requests to the original top-level HWND, never to the JAWT child Frame. */
internal class EmbeddedDockableWindow(private val handle: HWND, override val state: WindowState) : DockableWindow {
    private val user = User32.INSTANCE
    private val nativeUser = com.sun.jna.NativeLibrary.getInstance("user32")
    private val dpiFunction by lazy {
        try {
            nativeUser.getFunction("GetDpiForWindow")
        } catch (error: UnsatisfiedLinkError) {
            Log.tag("EmbeddedDockableWindow").w(error) { "Per-window DPI unavailable; using toolkit DPI" }
            null
        }
    }
    override val coordinateScale: Double
        get() = (
            dpiFunction?.invokeInt(arrayOf(handle))?.takeIf { it > 0 }
                ?: Toolkit.getDefaultToolkit().screenResolution
        ).toDouble() / WINDOWS_BASE_DPI
    override val isAlive: Boolean get() = user.IsWindow(handle)
    override val placement: WindowPlacement
        get() = if (nativeFlag("IsZoomed")) WindowPlacement.Maximized else WindowPlacement.Floating
    override val isMinimized: Boolean get() = nativeFlag("IsIconic")

    private fun nativeFlag(name: String): Boolean = nativeUser.getFunction(name).invokeInt(arrayOf(handle)) != 0

    override val bounds: Rectangle
        get() {
            val rect = RECT()
            check(user.GetWindowRect(handle, rect)) { "Cannot read embedded host bounds" }
            return rect.toRectangle()
        }
    override val workArea: Rectangle
        get() {
            val monitor = user.MonitorFromWindow(handle, WinUser.MONITOR_DEFAULTTONEAREST)
            val info = WinUser.MONITORINFO()
            check(user.GetMonitorInfo(monitor, info).booleanValue()) { "Cannot read embedded host monitor" }
            return info.rcWork.toRectangle()
        }

    override fun prepare() {
        val initial = bounds
        state.position = WindowPosition(initial.x.dp, initial.y.dp)
        state.size = DpSize(initial.width.dp, initial.height.dp)
        state.placement = placement
        state.isMinimized = isMinimized
    }

    override suspend fun awaitToolkit() {
        when {
            state.isMinimized && !isMinimized -> user.ShowWindow(handle, WinUser.SW_MINIMIZE)

            state.placement == WindowPlacement.Maximized && placement != WindowPlacement.Maximized ->
                user.ShowWindow(handle, WinUser.SW_MAXIMIZE)

            !state.isMinimized && (isMinimized || placement != state.placement) ->
                user.ShowWindow(handle, WinUser.SW_RESTORE)
        }
        if (placement == WindowPlacement.Floating && !isMinimized) {
            val position = state.position
            check(
                user.SetWindowPos(
                    handle,
                    null,
                    (position.x.value).toInt(),
                    (position.y.value).toInt(),
                    (state.size.width.value).toInt(),
                    (state.size.height.value).toInt(),
                    WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE,
                ),
            ) { "Cannot apply embedded host docking bounds" }
        }
        delay(EMBEDDED_DOCK_POLL_MILLIS)
    }

    private fun RECT.toRectangle(): Rectangle = Rectangle(left, top, right - left, bottom - top)
}

private const val WINDOWS_BASE_DPI = 96.0

private const val EMBEDDED_DOCK_POLL_MILLIS = 16L
