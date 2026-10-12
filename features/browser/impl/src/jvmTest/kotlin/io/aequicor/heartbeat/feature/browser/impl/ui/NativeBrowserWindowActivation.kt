package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.io.File

/** Restores only this test's window once, including workers inheriting a hidden startup show state. */
internal fun ComposeWindow.activateNativeTestWindow() {
    val output = File("build/native-ui/compose-native-activation.txt")
    check(output.parentFile.mkdirs() || output.parentFile.isDirectory)
    output.writeText("before: ${nativeWindowVisibility()}\n")
    if (Platform.isWindows()) {
        val own = HWND(Native.getWindowPointer(this))
        User32.INSTANCE.ShowWindow(own, WinUser.SW_RESTORE)
        val isActivated = User32.INSTANCE.SetForegroundWindow(own)
        output.appendText("SetForegroundWindow accepted=$isActivated\n")
    } else {
        toFront()
        requestFocus()
    }
    output.appendText("after: ${nativeWindowVisibility()}\n")
}

/** Only own HWND geometry, session, and current thread's desktop are inspected. */
internal fun ComposeWindow.nativeWindowVisibility(): String {
    if (!Platform.isWindows() || !isDisplayable) return "nativeVisibility=unavailable"
    val own = HWND(Native.getWindowPointer(this))
    val users = User32.INSTANCE
    val bounds = RECT()
    val hasBounds = users.GetWindowRect(own, bounds)
    val session = IntByReference()
    val hasSession = Kernel32.INSTANCE.ProcessIdToSessionId(Kernel32.INSTANCE.GetCurrentProcessId(), session)
    return "visible=${users.IsWindowVisible(own)}, cloaked=${own.nativeCloaking()}, " +
        "hasBounds=$hasBounds, bounds=(${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}), " +
        "intersectsMonitor=${hasBounds && users.MonitorFromRect(bounds, 0) != null}, " +
        "session=${if (hasSession) session.value else null}, desktop=${currentDesktop()}, " +
        "ownership=${nativeForegroundOwnership()}"
}

/** DWM cloaking includes windows hidden by another Windows virtual desktop. Unknown status fails closed. */
internal fun HWND.nativeCloaking(): Int? {
    val value = IntByReference()
    val result = NativeLibrary.getInstance("dwmapi")
        .getFunction("DwmGetWindowAttribute", Function.ALT_CONVENTION)
        .invokeInt(arrayOf(this, 14, value, 4))
    return value.value.takeIf { result == 0 }
}

private fun currentDesktop(): String {
    val users = NativeLibrary.getInstance("user32")
    val desktop = users.getFunction("GetThreadDesktop", Function.ALT_CONVENTION)
        .invokePointer(arrayOf(Kernel32.INSTANCE.GetCurrentThreadId())) ?: return "unavailable"
    return Memory(1024).use { buffer ->
        val needed = IntByReference()
        val result = users.getFunction("GetUserObjectInformationW", Function.ALT_CONVENTION)
            .invokeInt(arrayOf(desktop, 2, buffer, buffer.size().toInt(), needed))
        if (result != 0) {
            buffer.getWideString(0)
        } else {
            "unavailable"
        }
    }
}
