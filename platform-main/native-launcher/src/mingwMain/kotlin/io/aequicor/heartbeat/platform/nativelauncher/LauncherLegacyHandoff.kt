@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import okio.FileSystem
import okio.Path
import platform.windows.GetDC
import platform.windows.GetDeviceCaps
import platform.windows.GetForegroundWindow
import platform.windows.GetMonitorInfoW
import platform.windows.GetWindowPlacement
import platform.windows.HWND
import platform.windows.IsIconic
import platform.windows.IsZoomed
import platform.windows.LOGPIXELSX
import platform.windows.LOGPIXELSY
import platform.windows.MONITORINFO
import platform.windows.MONITOR_DEFAULTTONEAREST
import platform.windows.MonitorFromWindow
import platform.windows.ReleaseDC
import platform.windows.WINDOWPLACEMENT
import platform.windows.WPF_RESTORETOMAXIMIZED

internal fun readLegacyHandoff(window: HWND): HandoffPresentation = memScoped {
    val placement = alloc<WINDOWPLACEMENT>()
    placement.length = sizeOf<WINDOWPLACEMENT>().toUInt()
    check(GetWindowPlacement(window, placement.ptr) != 0) { "Cannot read native window placement" }
    val rect = placement.rcNormalPosition
    val monitor = alloc<MONITORINFO>()
    monitor.cbSize = sizeOf<MONITORINFO>().toUInt()
    check(GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST.toUInt()), monitor.ptr) != 0) {
        "Cannot read native window monitor"
    }
    val offsetX = monitor.rcWork.left - monitor.rcMonitor.left
    val offsetY = monitor.rcWork.top - monitor.rcMonitor.top
    val dc = GetDC(null)
    val dpiX = GetDeviceCaps(dc, LOGPIXELSX).coerceAtLeast(DEFAULT_DPI)
    val dpiY = GetDeviceCaps(dc, LOGPIXELSY).coerceAtLeast(DEFAULT_DPI)
    ReleaseDC(null, dc)
    HandoffPresentation(
        GetForegroundWindow() == window,
        IsIconic(window) != 0,
        (rect.left + offsetX) * DEFAULT_DPI / dpiX,
        (rect.top + offsetY) * DEFAULT_DPI / dpiY,
        (rect.right - rect.left) * DEFAULT_DPI / dpiX,
        (rect.bottom - rect.top) * DEFAULT_DPI / dpiY,
        IsZoomed(window) != 0 || (placement.flags and WPF_RESTORETOMAXIMIZED.toUInt()) != 0u,
    )
}

internal fun writeLegacyHandoff(window: HWND?, directory: okio.Path) {
    okio.FileSystem.SYSTEM.write(directory.resolve("presentation.properties")) {
        writeUtf8(readLegacyHandoff(checkNotNull(window)).properties())
    }
}

private const val DEFAULT_DPI = 96
