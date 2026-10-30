@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.macosui.hb_macos_create
import io.aequicor.heartbeat.platform.nativelauncher.macosui.hb_macos_dispose
import io.aequicor.heartbeat.platform.nativelauncher.macosui.hb_macos_finish
import io.aequicor.heartbeat.platform.nativelauncher.macosui.hb_macos_get_presentation
import io.aequicor.heartbeat.platform.nativelauncher.macosui.hb_macos_hide
import io.aequicor.heartbeat.platform.nativelauncher.macosui.hb_macos_prepare_launch
import io.aequicor.heartbeat.platform.nativelauncher.macosui.hb_macos_run
import io.aequicor.heartbeat.platform.nativelauncher.macosui.hb_macos_update
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.BootstrapLayout
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionProgress
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionStage
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlin.system.exitProcess

/** AppKit appears before provisioning/JVM; worker invocations inherit stdio and create no NSApplication. */
fun main(args: Array<String>) {
    val config = LauncherConfig.load()
    if (args.any { it.startsWith("--heartbeat-") }) {
        val image = checkNotNull(config.currentImage(ImageStore(config.imageRoot))) { "No compatible worker image" }
        val path = image.directory.resolve(BootstrapLayout.forPlatform("macos", config.channel).executable)
        exitProcess(UnixChild.start(path, args.toList()).waitForExit())
    }
    exitProcess(MacLauncher(config, args.toList()).run())
}

private class MacLauncher(config: LauncherConfig, args: List<String>) {
    private val session = BootstrapSession(config, args)
    private var window: COpaquePointer? = null
    private var previous: BootstrapSession.State? = null
    private var exitCode = 0

    fun run(): Int {
        val reference = StableRef.create(this)
        try {
            window = checkNotNull(
                hb_macos_create(
                    reference.asCPointer(),
                    staticCFunction { context: COpaquePointer?, action: Int ->
                        context?.asStableRef<MacLauncher>()?.get()?.action(action)
                        Unit
                    },
                    staticCFunction { context: COpaquePointer? ->
                        context?.asStableRef<MacLauncher>()?.get()?.tick()
                        Unit
                    },
                ),
            ) { "Cannot create native launcher window" }
            session.start()
            hb_macos_run()
            return exitCode
        } finally {
            session.dispose()
            window?.let(::hb_macos_dispose)
            reference.dispose()
        }
    }

    private fun action(action: Int) {
        if (action == 0) {
            hb_macos_hide(window)
            session.cancel()
        } else if (session.state.value is BootstrapSession.State.Failed) {
            session.start()
        }
    }

    private fun tick() {
        try {
            render()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            launcherLog.w("Launcher", error) { "Native launcher UI failed" }
            exitCode = 1
            session.cancel()
        }
    }

    private fun render() {
        val state = session.state.value
        if (state == previous) return
        previous = state
        when (state) {
            BootstrapSession.State.Preparing -> update("Подготовка приложения…")

            is BootstrapSession.State.Downloading -> renderProgress(state.progress)

            is BootstrapSession.State.ReadyToLaunch -> {
                update("Запуск приложения…")
                hb_macos_prepare_launch()
                session.launch(state.image)
            }

            BootstrapSession.State.Launching -> update("Запуск приложения…")

            is BootstrapSession.State.Handoff -> {
                val presentation = hb_macos_get_presentation(window, state.childPid).useContents {
                    HandoffPresentation(activate != 0, minimized != 0, x, y, width, height, maximized != 0)
                }
                hb_macos_hide(window)
                session.acknowledgeHandoff(state.directory, presentation)
            }

            BootstrapSession.State.Running, BootstrapSession.State.Cancelling -> Unit

            is BootstrapSession.State.Failed -> update(state.message, failed = true)

            is BootstrapSession.State.Finished -> {
                if (exitCode == 0) exitCode = state.code
                hb_macos_finish(window)
            }
        }
    }

    private fun renderProgress(progress: ProvisionProgress) {
        val stage = when (progress.stage) {
            ProvisionStage.MANIFEST -> "Проверка компонентов…"
            ProvisionStage.DOWNLOAD -> "Загрузка компонентов…"
            ProvisionStage.EXTRACT -> "Подготовка компонентов…"
            ProvisionStage.ACTIVATE -> "Завершение подготовки…"
        }
        val bytes = if (progress.totalBytes > 0) "${progress.downloadedBytes} / ${progress.totalBytes} байт" else " "
        val fraction = if (progress.totalBytes > 0) {
            progress.downloadedBytes.toDouble() / progress.totalBytes
        } else {
            -1.0
        }
        update(stage, bytes, fraction)
    }

    private fun update(stage: String, bytes: String = " ", fraction: Double = -1.0, failed: Boolean = false) {
        hb_macos_update(window, stage, bytes, fraction, if (failed) 1 else 0)
    }
}
