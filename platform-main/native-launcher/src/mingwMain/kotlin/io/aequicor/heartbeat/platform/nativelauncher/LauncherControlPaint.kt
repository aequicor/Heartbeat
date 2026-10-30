@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import platform.windows.HWND
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.UINT
import platform.windows.WPARAM

private typealias ControlProcedure = CPointer<CFunction<(HWND?, UINT, WPARAM, LPARAM) -> LRESULT>>

/** UI-thread-only Win32 subclass table; owner-draw controls must never erase with the system button brush. */
private val controlProcedures = mutableMapOf<HWND, ControlProcedure>()

internal fun suppressControlErase(window: HWND) {
    val previous = platform.windows.SetWindowLongPtrW(
        window,
        platform.windows.GWLP_WNDPROC,
        staticCFunction(::controlPaintProcedure).rawValue.toLong(),
    )
    check(previous != 0L) { "Cannot install the native control paint procedure" }
    controlProcedures[window] = checkNotNull(previous.toCPointer())
}

private fun controlPaintProcedure(window: HWND?, message: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT {
    if (message.toInt() == platform.windows.WM_ERASEBKGND) return 1L
    val previous = controlProcedures[window] ?: return platform.windows.DefWindowProcW(window, message, wParam, lParam)
    val result = platform.windows.CallWindowProcW(previous, window, message, wParam, lParam)
    if (message.toInt() == platform.windows.WM_NCDESTROY) controlProcedures.remove(window)
    return result
}
