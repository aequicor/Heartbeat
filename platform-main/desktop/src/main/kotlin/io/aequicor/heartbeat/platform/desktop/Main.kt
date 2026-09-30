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
import java.net.URISyntaxException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities

fun main(arguments: Array<String>) {
    if (runPackagedBuildWorker(arguments)) return
    launchHeartbeat(isDevelopment = false)
}

/** Shared desktop host; its entry point determines credential storage, never a runtime environment override. */
internal fun launchHeartbeat(isDevelopment: Boolean) {
    val classes = checkoutClasses()
    // Launches from the checkout's compiled classes log from DEBUG up; any jar, installed or not, logs as release.
    // `heartbeat.trace` (system property or HEARTBEAT_TRACE) adds the VERBOSE level for deep debugging.
    Log.init(isDebug = isDevelopment || classes.getOrNull() != null, isTrace = isTraceRequested())
    val log = Log.tag("Desktop")
    classes.onFailure { log.w(it) { "Entry point location is unknown; treating the launch as packaged" } }
    classes.getOrNull()?.let(::attachLocalPiRuntime)
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

/** Whether deep tracing (`VERBOSE`) was requested through `heartbeat.trace` or `HEARTBEAT_TRACE`. */
private fun isTraceRequested(): Boolean = System.getProperty("heartbeat.trace")?.toBooleanStrictOrNull()
    ?: System.getenv("HEARTBEAT_TRACE")?.toBooleanStrictOrNull()
    ?: false

/**
 * Class directory this entry point was loaded from, or null when it runs from a jar. Only IDE and Gradle
 * launches of a checkout load classes from a directory; distributions and uber jars never do.
 * Runs before logging is initialized, so a failure is returned for the caller to log.
 */
private fun checkoutClasses(): Result<Path?> = try {
    Result.success(
        object {}.javaClass.protectionDomain.codeSource?.location?.toURI()?.let(Path::of)?.takeIf(Files::isDirectory),
    )
} catch (e: URISyntaxException) {
    Result.failure(e)
} catch (e: IllegalArgumentException) {
    Result.failure(e)
} catch (e: SecurityException) {
    Result.failure(e)
} catch (e: FileSystemNotFoundException) {
    Result.failure(e)
}
private val isMacHost = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

private fun <T> runOnUiThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    val task = FutureTask(block)
    SwingUtilities.invokeAndWait(task)
    return task.get()
}

private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"

// Output of preparePiRuntime (platform-main/desktop/build.gradle.kts) relative to the checkout root.
private const val LOCAL_PI_RESOURCES = "platform-main/desktop/build/generated/piResources/common"

/**
 * Points a plain IDE `main()` launch from [classes] at the Pi runtime prepared by the Gradle build of the same
 * checkout: the search climbs from the classes to the checkout root and never leaves it. Gradle runs set the
 * resources directory themselves and are left untouched.
 */
private fun attachLocalPiRuntime(classes: Path) {
    val log = Log.tag("Desktop")
    if (System.getProperty(RESOURCES_DIR_PROPERTY) != null) return
    val checkout = generateSequence(classes) { it.parent }.firstOrNull { Files.isRegularFile(it.resolve(SETTINGS)) }
    val local = checkout?.resolve(LOCAL_PI_RESOURCES)
    val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "pi.exe" else "pi"
    if (local == null || !Files.isRegularFile(local.resolve("pi").resolve(executable))) {
        log.w { "Local Pi runtime not found; build the project once so preparePiRuntime unpacks it" }
    } else {
        log.i { "Using local Pi runtime from the project build" }
        log.d { "Local Pi runtime: $local" }
        System.setProperty(RESOURCES_DIR_PROPERTY, local.toString())
    }
}

private const val SETTINGS = "settings.gradle.kts"
