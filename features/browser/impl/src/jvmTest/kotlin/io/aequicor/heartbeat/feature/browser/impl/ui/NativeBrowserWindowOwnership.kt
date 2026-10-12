package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.ptr.IntByReference

/** Native foreground ownership without retaining any foreign window handle, PID, or title. */
internal data class NativeBrowserWindowOwnership(
    val isOwnProcess: Boolean,
    val isTestRoot: Boolean,
    val isTestRootOwner: Boolean,
    val isVisibleOnMonitor: Boolean,
) {
    fun permitsInput(areOwnedDialogsAllowed: Boolean): Boolean =
        isVisibleOnMonitor && isOwnProcess && (isTestRoot || (areOwnedDialogsAllowed && isTestRootOwner))
}

/** AWT focus events may precede actual Windows foreground activation, so they cannot authorize Robot. */
internal fun ComposeWindow.nativeForegroundOwnership(): NativeBrowserWindowOwnership? {
    if (!Platform.isWindows() || !isDisplayable) return null
    val users = User32.INSTANCE
    val foreground = users.GetForegroundWindow() ?: return NativeBrowserWindowOwnership(false, false, false, false)
    val process = IntByReference()
    users.GetWindowThreadProcessId(foreground, process)
    val own = HWND(Native.getWindowPointer(this))
    val bounds = RECT()
    val isVisible = users.IsWindowVisible(own) && own.nativeCloaking() == 0 && users.GetWindowRect(own, bounds) &&
        bounds.right > bounds.left && bounds.bottom > bounds.top && users.MonitorFromRect(bounds, 0) != null
    return NativeBrowserWindowOwnership(
        isOwnProcess = process.value.toLong() == ProcessHandle.current().pid(),
        isTestRoot = users.GetAncestor(foreground, 2) == own,
        isTestRootOwner = users.GetAncestor(foreground, 3) == own,
        isVisibleOnMonitor = isVisible,
    )
}
