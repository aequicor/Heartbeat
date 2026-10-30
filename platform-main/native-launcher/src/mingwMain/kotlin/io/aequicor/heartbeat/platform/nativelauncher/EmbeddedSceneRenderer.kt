// Header coordinates mirror the approved launcher frame.
@file:Suppress("MagicNumber")

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.desktop.EmbeddedIntroTimeline
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import platform.windows.GetTickCount64

/** The approved native cover shares its artwork and join frame with the Compose continuation. */
internal class EmbeddedSceneRenderer(private val density: Double) : AutoCloseable {
    private val theme = LauncherTheme.current()
    private val canvas = LauncherCanvas(density)
    private val scene = LauncherScene(theme)
    private val isIntroPreview = launcherEnvironment("HEARTBEAT_EMBEDDED_INTRO_PREVIEW") == "1"
    private val started = launcherEnvironment("HEARTBEAT_EMBEDDED_STARTED")?.toLongOrNull() ?: GetTickCount64().toLong()
    private val motion = LauncherMotion(true, false, started)

    fun paint(dc: COpaquePointer, width: Int, height: Int) {
        val now = started + EmbeddedIntroTimeline.nativeElapsed(GetTickCount64().toLong(), started, isIntroPreview)
        val status = LauncherSceneStatus()
        motion.tick(now, status, false)
        canvas.paint(dc.reinterpret(), width, height) {
            scene.draw(
                this,
                (width / density).toInt(),
                (height / density).toInt(),
                status,
                motion.frame(now, status),
                0,
                true,
            )
            text(
                "Heartbeat",
                12,
                0,
                160,
                theme.textSecondary,
                alignment = platform.windows.DT_LEFT,
                isCaptionTitle = true,
            )
        }
    }

    override fun close() = canvas.close()
}
