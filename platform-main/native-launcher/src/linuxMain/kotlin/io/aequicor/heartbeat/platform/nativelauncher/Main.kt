@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.linuxui.hb_gtk_create
import io.aequicor.heartbeat.platform.nativelauncher.linuxui.hb_gtk_dispose
import io.aequicor.heartbeat.platform.nativelauncher.linuxui.hb_gtk_get_presentation
import io.aequicor.heartbeat.platform.nativelauncher.linuxui.hb_gtk_hide
import io.aequicor.heartbeat.platform.nativelauncher.linuxui.hb_gtk_iterate
import io.aequicor.heartbeat.platform.nativelauncher.linuxui.hb_gtk_update
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.BootstrapLayout
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionProgress
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionStage
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import platform.posix.usleep
import kotlin.system.exitProcess

/** GTK3/X11 window precedes provisioning and JVM. Auxiliary workers retain inherited stdio without loading GTK. */
fun main(args: Array<String>) {
    val config = LauncherConfig.load()
    if (args.any { it.startsWith("--heartbeat-") }) {
        val image = checkNotNull(config.currentImage(ImageStore(config.imageRoot))) { "No compatible worker image" }
        val path = image.directory.resolve(BootstrapLayout.forPlatform("linux", config.channel).executable)
        exitProcess(UnixChild.start(path, args.toList()).waitForExit())
    }
    exitProcess(LinuxLauncher(config, args.toList()).run())
}

private class LinuxLauncher(config: LauncherConfig, args: List<String>) {
    private val session = BootstrapSession(config, args)
    private var window: COpaquePointer? = null
    private var previous: BootstrapSession.State? = null
    private var failureCode = 0

    fun run(): Int {
        val icon = checkNotNull(nativeLauncherExecutable().parent).resolve("../share/heartbeat/heartbeat.png")
        window = checkNotNull(hb_gtk_create(icon.toString())) {
            "Cannot create launcher window: GTK3 and an X11/XWayland display are required"
        }
        try {
            session.start()
            while (true) {
                tick()
                val finished = session.state.value as? BootstrapSession.State.Finished
                if (finished != null) return if (failureCode == 0) finished.code else failureCode
                usleep(POLL_MICROS)
            }
        } finally {
            session.dispose()
            hb_gtk_dispose(window)
        }
    }

    private fun tick() {
        when (hb_gtk_iterate(window)) {
            ACTION_CANCEL -> {
                hb_gtk_hide(window)
                session.cancel()
            }

            ACTION_RETRY -> if (session.state.value is BootstrapSession.State.Failed) session.start()
        }
        val state = session.state.value
        if (state != previous) {
            previous = state
            try {
                render(state)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                launcherLog.w("Launcher", error) { "Native launcher UI failed" }
                failureCode = 1
                hb_gtk_hide(window)
                session.cancel()
            }
        }
    }

    private fun render(state: BootstrapSession.State) {
        when (state) {
            BootstrapSession.State.Preparing -> update("Подготовка приложения…")

            is BootstrapSession.State.Downloading -> progress(state.progress)

            is BootstrapSession.State.ReadyToLaunch -> {
                update("Запуск приложения…")
                session.launch(state.image)
            }

            BootstrapSession.State.Launching -> update("Запуск приложения…")

            is BootstrapSession.State.Handoff -> {
                val presentation = hb_gtk_get_presentation(window).useContents {
                    HandoffPresentation(activate != 0, minimized != 0, x, y, width, height, maximized != 0)
                }
                hb_gtk_hide(window)
                session.acknowledgeHandoff(state.directory, presentation)
            }

            is BootstrapSession.State.Failed -> update(state.message, failed = true)

            BootstrapSession.State.Cancelling,
            BootstrapSession.State.Running,
            is BootstrapSession.State.Finished,
            -> Unit
        }
    }

    private fun progress(progress: ProvisionProgress) {
        val stage = when (progress.stage) {
            ProvisionStage.MANIFEST -> "Проверка компонентов…"
            ProvisionStage.DOWNLOAD -> "Загрузка компонентов…"
            ProvisionStage.EXTRACT -> "Подготовка компонентов…"
            ProvisionStage.ACTIVATE -> "Завершение подготовки…"
        }
        val bytes = if (progress.totalBytes > 0) "${progress.downloadedBytes} / ${progress.totalBytes} байт" else " "
        val fraction = if (progress.totalBytes > 0) progress.downloadedBytes.toDouble() / progress.totalBytes else -1.0
        update(stage, bytes, fraction)
    }

    private fun update(stage: String, bytes: String = " ", fraction: Double = -1.0, failed: Boolean = false) {
        hb_gtk_update(window, stage, bytes, fraction, if (failed) 1 else 0)
    }

    companion object {
        private const val ACTION_CANCEL = 1
        private const val ACTION_RETRY = 2
        private const val POLL_MICROS = 20_000u
    }
}
