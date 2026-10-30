@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.wcstr
import okio.FileSystem
import okio.Path
import platform.windows.GetWindowPlacement
import platform.windows.GetWindowRect
import platform.windows.HWND
import platform.windows.IsIconic
import platform.windows.IsZoomed
import platform.windows.RECT
import platform.windows.WINDOWPLACEMENT

/** Revision prevents a delayed acknowledgement from revealing a newly moved native window. */
internal data class LauncherPresentation(val body: String, val revision: Long) {
    val record: String get() = "revision=$revision\n$body"
}

/** v2 uses physical coordinates and normal placement when minimized, never iconic (-32000, -32000) bounds. */
internal fun writeHandoffPresentation(
    window: HWND?,
    directory: Path,
    theme: LauncherTheme,
    previous: LauncherPresentation? = null,
    isMoving: Boolean = false,
    forcePublication: Boolean = false,
): LauncherPresentation? = memScoped {
    val rect = alloc<RECT>()
    check(GetWindowRect(window, rect.ptr) != 0) { "Cannot read launcher bounds" }
    val normal = alloc<RECT>()
    val restoreMaximized = readNormalPlacement(window, normal)
    val minimized = IsIconic(window) != 0
    val maximized = IsZoomed(window) != 0 || (minimized && restoreMaximized)
    if (minimized) {
        rect.left = normal.left
        rect.top = normal.top
        rect.right = normal.right
        rect.bottom = normal.bottom
    }
    val snapshot = "x=${rect.left}\ny=${rect.top}\nwidth=${rect.right - rect.left}\n" +
        "height=${rect.bottom - rect.top}\ndark=${theme.isDark}\nmaximized=$maximized\n" +
        "minimized=$minimized\nnormalX=${normal.left}\nnormalY=${normal.top}\n" +
        "normalWidth=${normal.right - normal.left}\nnormalHeight=${normal.bottom - normal.top}\n" +
        "nativeHandle=${window?.rawValue?.toLong() ?: 0L}\nnativePid=${platform.windows.GetCurrentProcessId()}\n" +
        "moving=$isMoving\n"
    if (!forcePublication && previous?.body == snapshot) return@memScoped previous
    val presentation = LauncherPresentation(snapshot, (previous?.revision ?: 0L) + 1)
    val fs = FileSystem.SYSTEM
    val temporary = directory.resolve("window.tmp")
    fs.write(temporary) { writeUtf8(presentation.record) }
    val moved = platform.windows.MoveFileExW(
        temporary.toString().wcstr.getPointer(this),
        directory.resolve("window.properties").toString().wcstr.getPointer(this),
        (platform.windows.MOVEFILE_REPLACE_EXISTING or platform.windows.MOVEFILE_WRITE_THROUGH).toUInt(),
    )
    if (moved != 0) return@memScoped presentation
    val error = platform.windows.GetLastError().toInt()
    // Keep the old revision on a transient reader/antivirus lock and retry on the next timer tick.
    check(error == platform.windows.ERROR_ACCESS_DENIED || error == platform.windows.ERROR_SHARING_VIOLATION) {
        "Cannot publish handoff window revision: Win32 error $error"
    }
    null
}

private fun readNormalPlacement(window: HWND?, rect: RECT): Boolean = memScoped {
    val placement = alloc<WINDOWPLACEMENT>()
    placement.length = sizeOf<WINDOWPLACEMENT>().toUInt()
    check(GetWindowPlacement(window, placement.ptr) != 0) { "Cannot read normal launcher placement" }
    val monitor = platform.windows.MonitorFromWindow(window, platform.windows.MONITOR_DEFAULTTONEAREST.toUInt())
    val info = alloc<platform.windows.MONITORINFO>()
    info.cbSize = sizeOf<platform.windows.MONITORINFO>().toUInt()
    check(platform.windows.GetMonitorInfoW(monitor, info.ptr) != 0) { "Cannot read launcher monitor work area" }
    val dx = info.rcWork.left - info.rcMonitor.left
    val dy = info.rcWork.top - info.rcMonitor.top
    rect.left = placement.rcNormalPosition.left + dx
    rect.right = placement.rcNormalPosition.right + dx
    rect.top = placement.rcNormalPosition.top + dy
    rect.bottom = placement.rcNormalPosition.bottom + dy
    placement.flags.toInt() and platform.windows.WPF_RESTORETOMAXIMIZED != 0
}
