@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.memScoped
import kotlinx.cinterop.wcstr
import platform.windows.BS_OWNERDRAW
import platform.windows.CreateWindowExW
import platform.windows.GetModuleHandleW
import platform.windows.HWND
import platform.windows.SS_LEFT
import platform.windows.SW_HIDE
import platform.windows.SendMessageW
import platform.windows.ShowWindow
import platform.windows.WM_SETFONT
import platform.windows.WS_CHILD
import platform.windows.WS_VISIBLE

internal fun LauncherWindow.createControls(window: HWND) {
    cancelButton = control("BUTTON", "Отмена", window, BS_OWNERDRAW)
    retryButton = control("BUTTON", "Повторить", window, BS_OWNERDRAW)
    previousTip = control("BUTTON", "Предыдущая подсказка", window, BS_OWNERDRAW)
    nextTip = control("BUTTON", "Следующая подсказка", window, BS_OWNERDRAW)
    listOf(cancelButton, retryButton, previousTip, nextTip).forEach { ShowWindow(it, SW_HIDE) }
    captionText = control("STATIC", "Heartbeat", window, SS_LEFT)
    minimizeButton = control("BUTTON", "Свернуть", window, BS_OWNERDRAW)
    maximizeButton = control("BUTTON", "Развернуть", window, BS_OWNERDRAW)
    closeButton = control("BUTTON", "Закрыть", window, BS_OWNERDRAW)
    captionFont?.let { font ->
        SendMessageW(captionText, WM_SETFONT.toUInt(), font.rawValue.toLong().toULong(), 1L)
    }
    layout(window)
}

private fun control(className: String, text: String, parent: HWND, style: Int = 0): HWND? = memScoped {
    val focusStyle = if (className == "BUTTON") platform.windows.WS_TABSTOP else 0
    CreateWindowExW(
        0u,
        className.wcstr.getPointer(this),
        text.wcstr.getPointer(this),
        (WS_CHILD or WS_VISIBLE or style or focusStyle).toUInt(),
        0, 0, 0, 0,
        parent,
        null,
        GetModuleHandleW(null),
        null,
    )?.also { if (className == "BUTTON") suppressControlErase(it) }
}
