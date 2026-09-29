package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbWindowDragProvider
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.platform.dibundle.createHeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.shared.App
import io.aequicor.heartbeat.platform.shared.createAppRoot
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities

fun main() {
    launchHeartbeat(isDevelopment = false)
}

/** Shared desktop host; its entry point determines credential storage, never a runtime environment override. */
internal fun launchHeartbeat(isDevelopment: Boolean) {
    // Unpackaged launches (IDE main(), Gradle runs) print every level; installed apps keep release logging.
    Log.init(isDebug = isDevelopment || !isPackagedApp)
    attachLocalPiRuntime()
    val lifecycle = LifecycleRegistry()
    val root = runOnUiThread {
        createAppRoot(DefaultComponentContext(lifecycle), createHeartbeatGraph(isDevelopment))
    }
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
            onPreviewKeyEvent = { event -> openSettingsOnShortcut(event, root) },
        ) {
            DisposableEffect(window) {
                configureDesktopChrome(window.rootPane)
                onDispose { }
            }
            HbWindowDragProvider { App(root) }
        }
    }
}

/**
 * ⌘, on macOS and Ctrl+, elsewhere open the settings window from anywhere in the window. The platform knows only
 * the link: the settings feature owns the route and decides what the link opens.
 */
private fun openSettingsOnShortcut(event: KeyEvent, root: HeartbeatRoot): Boolean {
    val isAccelerator = if (isMacHost) event.isMetaPressed && !event.isCtrlPressed else event.isCtrlPressed
    if (event.type != KeyEventType.KeyDown || event.key != Key.Comma || !isAccelerator) return false
    Log.tag("Desktop").i { "settings shortcut" }
    root.handleDeepLink(SETTINGS_LINK)
    return true
}

private const val SETTINGS_LINK = "heartbeat://settings"

/** jpackage launchers of distributions set this property; its absence means a launch from the build output. */
private val isPackagedApp = System.getProperty("jpackage.app-path") != null
private val isMacHost = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

private fun <T> runOnUiThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    val task = FutureTask(block)
    SwingUtilities.invokeAndWait(task)
    return task.get()
}

private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"
private const val LOCAL_PI_RESOURCES = "platform-main/desktop/build/generated/piResources/common"

/**
 * Points a plain IDE `main()` launch at the Pi runtime prepared by the Gradle build of this checkout.
 * Gradle runs and distributions set the resources directory themselves and are left untouched.
 */
private fun attachLocalPiRuntime() {
    val log = Log.tag("Desktop")
    if (System.getProperty(RESOURCES_DIR_PROPERTY) != null) return
    val local = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve(LOCAL_PI_RESOURCES) }
        .firstOrNull { Files.isDirectory(it) }
    if (local == null) {
        log.w { "Local Pi runtime not found; build the project once so preparePiRuntime unpacks it" }
    } else {
        log.i { "Using local Pi runtime from the project build" }
        System.setProperty(RESOURCES_DIR_PROPERTY, local.toString())
    }
}
