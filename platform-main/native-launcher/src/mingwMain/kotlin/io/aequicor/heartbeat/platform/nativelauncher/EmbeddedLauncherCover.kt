@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_close_cover
import io.aequicor.heartbeat.platform.nativelauncher.windowsjvm.hb_jvm_install_cover
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import platform.windows.HDC
import platform.windows.HWND

/** Opaque sibling over the live AWT peer; JNI releases it only after Compose paints its matching join frame. */
internal fun installEmbeddedCover(window: HWND, density: Double) {
    val reference = StableRef.create(EmbeddedCover(window, EmbeddedSceneRenderer(density)))
    if (hb_jvm_install_cover(
            window,
            staticCFunction(::paintEmbeddedCover),
            staticCFunction(::disposeEmbeddedCover),
            reference.asCPointer(),
        ) == 0
    ) {
        hb_jvm_close_cover()
        error("Cannot create the embedded application cover")
    }
}

private class EmbeddedCover(val window: HWND, val renderer: EmbeddedSceneRenderer) {
    var hasFailed = false
}

private fun paintEmbeddedCover(context: COpaquePointer?, dc: HDC?, width: Int, height: Int) {
    val cover = context?.asStableRef<EmbeddedCover>()?.get() ?: return
    if (cover.hasFailed || dc == null) return
    try {
        cover.renderer.paint(dc, width, height)
    } catch (error: IllegalStateException) {
        cover.hasFailed = true
        launcherLog.w("EmbeddedCover", error) { "Native cover rendering failed; cancelling startup" }
        platform.windows.PostMessageW(cover.window, platform.windows.WM_CLOSE.toUInt(), 0u, 0)
    }
}

private fun disposeEmbeddedCover(context: COpaquePointer?) {
    val reference = context?.asStableRef<EmbeddedCover>() ?: return
    try {
        reference.get().renderer.close()
    } catch (error: IllegalStateException) {
        launcherLog.w("EmbeddedCover", error) { "Native cover resource disposal failed" }
    } finally {
        reference.dispose()
    }
}
