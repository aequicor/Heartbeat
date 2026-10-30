@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import platform.windows.DRAWITEMSTRUCT
import platform.windows.HWND
import platform.windows.IsZoomed
import kotlin.math.roundToInt

/** Native hit targets keep their keyboard/accessibility semantics; all pixels use the scene's coverage renderer. */
internal class LauncherControlFrames : AutoCloseable {
    private val frames = mutableMapOf<HWND, LauncherCanvas>()

    fun draw(window: LauncherWindow, item: DRAWITEMSTRUCT) {
        val handle = checkNotNull(item.hwndItem)
        val density = window.scale(DPI).toDouble() / DPI
        val canvas = frames.getOrPut(handle) { LauncherCanvas(density) }
        val width = item.rcItem.right - item.rcItem.left
        val height = item.rcItem.bottom - item.rcItem.top
        val logicalWidth = (width / density).roundToInt()
        val logicalHeight = (height / density).roundToInt()
        val primary = handle == window.retryButton
        val caption = isCaption(window, handle)
        val direction = window.tipDirection(handle)
        val base = when {
            caption || direction != null -> window.theme.background
            primary -> window.theme.primaryButton
            else -> window.theme.secondaryButton
        }
        val color = window.theme.interactionFill(
            base,
            item.itemState.toInt() and platform.windows.ODS_SELECTED != 0,
            handle == window.hoveredButton,
        )
        canvas.paint(checkNotNull(item.hDC), width, height) {
            fill(0, 0, logicalWidth, logicalHeight, window.theme.background)
            fill(0, 0, logicalWidth, logicalHeight, color, if (caption) 0 else RADIUS)
            when {
                caption -> caption(window, handle, logicalWidth, logicalHeight)

                direction != null -> chevron(logicalWidth, logicalHeight, direction, window.theme.textSecondary)

                else -> text(
                    if (primary) "Повторить" else "Отмена",
                    0,
                    (logicalHeight - TEXT_HEIGHT) / 2,
                    logicalWidth,
                    if (primary) window.theme.primaryButtonText else window.theme.textPrimary,
                    size = 12,
                    alignment = platform.windows.DT_CENTER or platform.windows.DT_VCENTER,
                )
            }
        }
    }

    private fun isCaption(window: LauncherWindow, handle: HWND) =
        handle == window.minimizeButton || handle == window.maximizeButton || handle == window.closeButton

    private fun LauncherCanvas.caption(window: LauncherWindow, handle: HWND, width: Int, height: Int) {
        val x = width / 2.0
        val y = height / 2.0
        val color = window.theme.textPrimary
        when (handle) {
            window.minimizeButton -> fill((x - HALF).toInt(), y.toInt(), HALF * 2, 1, color)

            window.closeButton -> {
                path(listOf(LauncherPoint(x - HALF, y - HALF), LauncherPoint(x + HALF, y + HALF)), color, 1)
                path(listOf(LauncherPoint(x - HALF, y + HALF), LauncherPoint(x + HALF, y - HALF)), color, 1)
            }

            else -> {
                if (IsZoomed(window.hwnd) != 0) {
                    box(x - HALF + 2, y - HALF, x + HALF, y + HALF - 2, color)
                    fill(
                        (x - HALF).toInt(),
                        (y - HALF + 2).toInt(),
                        HALF * 2 - 2,
                        HALF * 2 - 2,
                        window.theme.background,
                    )
                    box(x - HALF, y - HALF + 2, x + HALF - 2, y + HALF, color)
                } else {
                    box(x - HALF, y - HALF, x + HALF, y + HALF, color)
                }
            }
        }
    }

    private fun LauncherCanvas.box(left: Double, top: Double, right: Double, bottom: Double, color: UInt) = path(
        listOf(
            LauncherPoint(left, top),
            LauncherPoint(right, top),
            LauncherPoint(right, bottom),
            LauncherPoint(left, bottom),
            LauncherPoint(left, top),
        ),
        color,
        1,
    )

    private fun LauncherCanvas.chevron(width: Int, height: Int, direction: Int, color: UInt) {
        val x = width / 2.0
        val y = height / 2.0
        path(
            listOf(
                LauncherPoint(x - direction * 2, y - CHEVRON_HALF),
                LauncherPoint(x + direction * 2, y),
                LauncherPoint(x - direction * 2, y + CHEVRON_HALF),
            ),
            color,
            1,
        )
    }

    override fun close() {
        frames.values.forEach { it.close() }
        frames.clear()
    }

    companion object {
        private const val DPI = 96
        private const val RADIUS = 6
        private const val TEXT_HEIGHT = 32
        private const val HALF = 5
        private const val CHEVRON_HALF = 3
    }
}
