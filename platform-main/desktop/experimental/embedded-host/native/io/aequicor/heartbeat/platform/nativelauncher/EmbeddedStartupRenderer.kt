// Header coordinates mirror the approved launcher frame.
@file:Suppress("MagicNumber")

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.desktop.EmbeddedIntroTimeline
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import platform.windows.GetTickCount64

@kotlin.native.CName("hb_renderer_create")
fun createEmbeddedSceneRenderer(density: Double): COpaquePointer = StableRef.create(EmbeddedSceneRenderer(density)).asCPointer()

@kotlin.native.CName("hb_renderer_paint")
fun paintEmbeddedSceneRenderer(renderer: COpaquePointer, dc: COpaquePointer, width: Int, height: Int) {
    renderer.asStableRef<EmbeddedSceneRenderer>().get().paint(dc, width, height)
}

@kotlin.native.CName("hb_renderer_dispose")
fun disposeEmbeddedSceneRenderer(renderer: COpaquePointer) {
    val reference = renderer.asStableRef<EmbeddedSceneRenderer>()
    reference.get().close()
    reference.dispose()
}
