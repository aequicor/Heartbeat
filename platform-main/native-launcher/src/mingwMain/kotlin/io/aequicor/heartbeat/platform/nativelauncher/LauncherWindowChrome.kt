@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.DefWindowProcW
import platform.windows.GetClientRect
import platform.windows.GetMonitorInfoW
import platform.windows.GetProcAddress
import platform.windows.GetWindowRect
import platform.windows.HTCAPTION
import platform.windows.HTCLIENT
import platform.windows.HWND
import platform.windows.IsZoomed
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.LoadLibraryW
import platform.windows.MINMAXINFO
import platform.windows.MONITORINFO
import platform.windows.MONITOR_DEFAULTTONEAREST
import platform.windows.MonitorFromWindow
import platform.windows.POINT
import platform.windows.RECT
import platform.windows.ScreenToClient
import platform.windows.WM_NCHITTEST

/**
 * DWM-тень у окна без рамки (WM_NCCALCSIZE = 0): при 1px-полях DWM рендерит тень,
 * не показывая нативный фрейм. dwmapi нет в платформенном klib, поэтому вызов —
 * через GetProcAddress; MARGINS — четыре int подряд, передаём как массив.
 */
internal fun extendFrameIntoClientArea(window: HWND) = memScoped {
    val dwmapi = LoadLibraryW("dwmapi.dll".wcstr.getPointer(this)) ?: return@memScoped
    val address = GetProcAddress(dwmapi, "DwmExtendFrameIntoClientArea") ?: return@memScoped
    val extend = address.reinterpret<CFunction<(HWND?, CPointer<IntVar>?) -> Int>>()
    val raw = platform.posix.calloc(MARGINS_INTS.toULong(), INT32_BYTES.toULong()) ?: return@memScoped
    val margins = raw.reinterpret<IntVar>()
    for (index in 0 until MARGINS_INTS) {
        (margins + index.toLong())?.pointed?.value = FRAME_MARGIN_PX
    }
    extend(window, margins)
    platform.posix.free(raw)
}

/** Colors DWM's transient resize surface before the newly exposed client area has a painted frame. */
internal fun applyLauncherFrameTheme(window: HWND?, theme: LauncherTheme) = memScoped {
    val library = checkNotNull(LoadLibraryW("dwmapi.dll".wcstr.getPointer(this)))
    try {
        val address = checkNotNull(GetProcAddress(library, "DwmSetWindowAttribute"))
        val setAttribute = address.reinterpret<CFunction<(HWND?, UInt, CPointer<IntVar>?, UInt) -> Int>>()
        val value = alloc<IntVar>()
        value.value = if (theme.isDark) 1 else 0
        if (setAttribute(window, IMMERSIVE_DARK_MODE, value.ptr, sizeOf<IntVar>().toUInt()) != 0) {
            setAttribute(window, LEGACY_IMMERSIVE_DARK_MODE, value.ptr, sizeOf<IntVar>().toUInt())
        }
        // Caption colors are optional on older Windows; immersive-dark above is the supported fallback.
        value.value = theme.background.toInt()
        setAttribute(window, CAPTION_COLOR, value.ptr, sizeOf<IntVar>().toUInt())
    } finally {
        platform.windows.FreeLibrary(library)
    }
}

/** DWM's normal shrinking close animation would expose a second ghost window over the ready Studio frame. */
internal fun suppressHandoffCloseAnimation(window: HWND?) = memScoped {
    val library = checkNotNull(LoadLibraryW("dwmapi.dll".wcstr.getPointer(this)))
    try {
        val address = checkNotNull(GetProcAddress(library, "DwmSetWindowAttribute"))
        val setAttribute = address.reinterpret<CFunction<(HWND?, UInt, CPointer<IntVar>?, UInt) -> Int>>()
        val disabled = alloc<IntVar>()
        disabled.value = 1
        check(setAttribute(window, TRANSITIONS_FORCEDISABLED, disabled.ptr, sizeOf<IntVar>().toUInt()) == 0) {
            "Cannot suppress the launcher close transition"
        }
    } finally {
        platform.windows.FreeLibrary(library)
    }
}

/**
 * Hit-test поверх стандартного: системная caption-полоса (HTCAPTION) даёт перетаскивание и Aero Snap
 * как у рамочного окна; над нашими кнопками возвращаем HTCLIENT, чтобы они получали клики;
 * HTCLIENT в пределах полосы caption поднимаем до HTCAPTION (область заголовка для drag).
 * Зоны ресайза (HTTOP/HTLEFT/…) остаются дефолтным результатом и не перекрываются.
 */
internal fun LauncherWindow.onNcHitTest(lParam: LPARAM): LRESULT? = memScoped {
    val window = hwnd ?: return@memScoped null
    val screenX = (lParam.toInt() and 0xFFFF).toShort().toInt()
    val screenY = (lParam.toInt() shr 16).toShort().toInt()
    val base = DefWindowProcW(window, WM_NCHITTEST.toUInt(), 0uL, lParam)
    if (base != HTCAPTION.toLong() && base != HTCLIENT.toLong()) return@memScoped null
    val point = alloc<POINT>()
    point.x = screenX
    point.y = screenY
    ScreenToClient(window, point.ptr)
    launcherResizeHit(window, point, scale(RESIZE_EDGE))?.let { return@memScoped it }
    val overButton = listOfNotNull(minimizeButton, maximizeButton, closeButton).any { button ->
        val rect = alloc<RECT>()
        GetWindowRect(button, rect.ptr)
        screenX >= rect.left && screenX < rect.right && screenY >= rect.top && screenY < rect.bottom
    }
    if (overButton) return@memScoped HTCLIENT.toLong()
    if (point.y < 0 || point.y >= scale(LauncherWindow.CAPTION_HEIGHT)) return@memScoped null
    HTCAPTION.toLong()
}

/** Максимизация без перекрытия панели задач: разворачиваем на рабочую область монитора. */
internal fun LauncherWindow.onGetMinMaxInfo(lParam: LPARAM) {
    val info = lParam.toCPointer<MINMAXINFO>()?.pointed ?: return
    val window = hwnd ?: return
    info.ptMinTrackSize.x = scale(MINIMUM_WIDTH)
    info.ptMinTrackSize.y = scale(MINIMUM_HEIGHT)
    memScoped {
        val monitor = MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST.toUInt())
        val monitorInfo = alloc<MONITORINFO>()
        monitorInfo.cbSize = sizeOf<MONITORINFO>().toUInt()
        if (GetMonitorInfoW(monitor, monitorInfo.ptr) != 0) {
            info.ptMaxSize.x = monitorInfo.rcWork.right - monitorInfo.rcWork.left
            info.ptMaxSize.y = monitorInfo.rcWork.bottom - monitorInfo.rcWork.top
            info.ptMaxPosition.x = monitorInfo.rcWork.left - monitorInfo.rcMonitor.left
            info.ptMaxPosition.y = monitorInfo.rcWork.top - monitorInfo.rcMonitor.top
        }
    }
}

/** WM_NCCALCSIZE removes the visible frame; its resize hit areas must therefore be supplied explicitly. */
private fun launcherResizeHit(window: HWND, point: POINT, edge: Int): LRESULT? = memScoped {
    if (IsZoomed(window) != 0) return@memScoped null
    val rect = alloc<RECT>()
    GetClientRect(window, rect.ptr)
    val horizontal = when {
        point.x < edge -> platform.windows.HTLEFT
        point.x >= rect.right - edge -> platform.windows.HTRIGHT
        else -> 0
    }
    val vertical = when {
        point.y < edge -> platform.windows.HTTOP
        point.y >= rect.bottom - edge -> platform.windows.HTBOTTOM
        else -> 0
    }
    combineResizeEdges(horizontal, vertical).takeIf { it != 0 }?.toLong()
}

private fun combineResizeEdges(horizontal: Int, vertical: Int): Int = when (vertical) {
    platform.windows.HTTOP -> when (horizontal) {
        platform.windows.HTLEFT -> platform.windows.HTTOPLEFT
        platform.windows.HTRIGHT -> platform.windows.HTTOPRIGHT
        else -> vertical
    }

    platform.windows.HTBOTTOM -> when (horizontal) {
        platform.windows.HTLEFT -> platform.windows.HTBOTTOMLEFT
        platform.windows.HTRIGHT -> platform.windows.HTBOTTOMRIGHT
        else -> vertical
    }

    else -> horizontal
}

private const val RESIZE_EDGE = 6
private const val MINIMUM_WIDTH = 640
private const val MINIMUM_HEIGHT = 480

private const val FRAME_MARGIN_PX = 1

private const val MARGINS_INTS = 4

private const val INT32_BYTES = 4
private const val TRANSITIONS_FORCEDISABLED = 3u
private const val IMMERSIVE_DARK_MODE = 20u
private const val LEGACY_IMMERSIVE_DARK_MODE = 19u
private const val CAPTION_COLOR = 35u

// Hover geometry mirrors the approved logical tip-row layout.
@Suppress("MagicNumber")
internal fun isPointerOverLauncherTips(hwnd: HWND?, density: Double): Boolean = memScoped {
    val point = alloc<POINT>()
    val rect = alloc<RECT>()
    platform.windows.GetCursorPos(point.ptr)
    ScreenToClient(hwnd, point.ptr)
    GetClientRect(hwnd, rect.ptr)
    val width = (rect.right / density).toInt()
    val height = (rect.bottom / density).toInt()
    val sidebar = if (width >= 720) 264 else 0
    val cx = sidebar + (width - sidebar) / 2
    val top = minOf(height / 2 + 260, height - 120)
    (point.x / density).toInt() in cx - 290..cx + 290 && (point.y / density).toInt() in top - 8..top + 60
}
