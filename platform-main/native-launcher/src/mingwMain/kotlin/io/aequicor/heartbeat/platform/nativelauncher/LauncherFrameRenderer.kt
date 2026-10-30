@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellationException
import platform.windows.BeginPaint
import platform.windows.EndPaint
import platform.windows.GetClientRect
import platform.windows.GetTickCount64
import platform.windows.HDC
import platform.windows.HWND
import platform.windows.PAINTSTRUCT
import platform.windows.RECT

/** Owns the complete scene and its buffered WM_ERASEBKGND/WM_PAINT lifecycle, independent of child processes. */
internal class LauncherFrameRenderer(
    private val window: HWND,
    private val density: Double,
    private val motion: LauncherMotion,
    theme: LauncherTheme,
    private val status: () -> LauncherSceneStatus,
    private val isRussian: Boolean,
    private val onError: (Exception) -> Unit,
) : AutoCloseable {
    private val canvas = LauncherCanvas(density)
    private var scene = LauncherScene(theme)
    private var isEraseFrameReady = false
    private var isRenderingFailed = false

    fun paint() = memScoped {
        val paint = alloc<PAINTSTRUCT>()
        val dc = BeginPaint(window, paint.ptr) ?: return@memScoped
        try {
            render(dc, isEraseFrameReady)
            isEraseFrameReady = false
        } finally {
            EndPaint(window, paint.ptr)
        }
    }

    fun erase(dc: HDC?) {
        isEraseFrameReady = render(dc)
    }

    fun updateTheme(theme: LauncherTheme) {
        scene = LauncherScene(theme)
        isEraseFrameReady = false
    }

    private fun render(dc: HDC?, isReuseAllowed: Boolean = false): Boolean = memScoped {
        if (dc == null) return@memScoped false
        try {
            val rect = alloc<RECT>()
            GetClientRect(window, rect.ptr)
            if (isReuseAllowed && canvas.present(dc, rect.right, rect.bottom)) return@memScoped true
            val snapshot = status()
            val frame = motion.frame(GetTickCount64().toLong(), snapshot)
            canvas.paint(dc, rect.right, rect.bottom) {
                scene.draw(
                    this,
                    (rect.right / density).toInt(),
                    (rect.bottom / density).toInt(),
                    snapshot,
                    frame,
                    motion.tipIndex,
                    isRussian,
                )
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!isRenderingFailed) {
                isRenderingFailed = true
                launcherLog.w("Launcher", error) { "Cannot render startup scene" }
                onError(error)
            }
            false
        }
    }

    override fun close() = canvas.close()
}
