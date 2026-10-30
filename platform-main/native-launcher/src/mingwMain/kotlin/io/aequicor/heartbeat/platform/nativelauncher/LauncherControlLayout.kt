@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import platform.windows.HWND

/** Applies all child bounds together, then presents the parent and children before WM_SIZE returns. */
internal class LauncherControlLayout {
    private val placements = mutableListOf<Placement>()

    fun begin() = placements.clear()

    fun move(window: HWND?, x: Int, y: Int, width: Int, height: Int) {
        if (window != null) placements += Placement(window, x, y, width, height)
    }

    fun commit(parent: HWND) {
        if (placements.isNotEmpty()) {
            var batch = checkNotNull(platform.windows.BeginDeferWindowPos(placements.size))
            val flags = platform.windows.SWP_NOZORDER or platform.windows.SWP_NOACTIVATE or
                platform.windows.SWP_NOREDRAW or platform.windows.SWP_NOCOPYBITS
            placements.forEach {
                batch = checkNotNull(
                    platform.windows.DeferWindowPos(
                        batch,
                        it.window,
                        null,
                        it.x,
                        it.y,
                        it.width,
                        it.height,
                        flags.toUInt(),
                    ),
                )
            }
            check(platform.windows.EndDeferWindowPos(batch) != 0) { "Cannot commit native control layout" }
        }
        val repaintFlags = platform.windows.RDW_INVALIDATE or platform.windows.RDW_ALLCHILDREN or
            platform.windows.RDW_ERASE or platform.windows.RDW_UPDATENOW
        platform.windows.RedrawWindow(parent, null, null, repaintFlags.toUInt())
    }

    private data class Placement(val window: HWND, val x: Int, val y: Int, val width: Int, val height: Int)
}
