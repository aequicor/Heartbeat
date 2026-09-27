package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.platform.dibundle.createHeartbeatGraph
import io.aequicor.heartbeat.platform.shared.App
import io.aequicor.heartbeat.platform.shared.createAppRoot
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities

fun main() {
    Log.init(isDebug = true)
    val lifecycle = LifecycleRegistry()
    val root = runOnUiThread { createAppRoot(DefaultComponentContext(lifecycle), createHeartbeatGraph()) }
    val dimensions = HbDimensions()
    application {
        val windowState = rememberWindowState(width = dimensions.windowWidth, height = dimensions.windowHeight)
        LifecycleController(lifecycle, windowState)
        Window(
            onCloseRequest = {
                Log.tag("Desktop").i { "close window" }
                lifecycle.destroy()
                exitApplication()
            },
            title = "Heartbeat",
            state = windowState,
        ) { App(root) }
    }
}

private fun <T> runOnUiThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    val task = FutureTask(block)
    SwingUtilities.invokeAndWait(task)
    return task.get()
}
