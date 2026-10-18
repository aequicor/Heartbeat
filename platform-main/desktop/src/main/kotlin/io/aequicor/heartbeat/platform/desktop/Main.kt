package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbDesktopPopupRendering
import io.aequicor.heartbeat.ds.components.HbInWindowDialogHost
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.platform.dibundle.AppShutdownAccessors
import io.aequicor.heartbeat.platform.dibundle.HeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.PlantUmlAccessors
import io.aequicor.heartbeat.platform.dibundle.closeScopes
import io.aequicor.heartbeat.platform.dibundle.createHeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.shared.App
import io.aequicor.heartbeat.platform.shared.createAppRoot
import java.awt.Taskbar
import java.awt.image.BufferedImage
import java.net.URISyntaxException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

fun main(arguments: Array<String>) {
    if (runPackagedBuildWorker(arguments)) return
    if (runPackagedPlantUmlWorker(arguments)) return
    if (runPackagedHarnessCompilerWorker(arguments)) return
    if (runChromiumHost(arguments)) return
    if (handleWindowRuntimeProbe(arguments)) return
    if (handleComputerUseIndicatorProbe(arguments)) return
    if (handleHarnessCompilerProbe(arguments)) return
    launchHeartbeat(isDevelopment = false)
}

/**
 * Shared desktop host; its entry point determines credential storage, never a runtime environment override.
 * [hasLocalSecrets] keeps credentials in the local development files instead of Keychain/DPAPI/Secret Service:
 * development runs always do, the testing flavor does while staying a release launch otherwise.
 */
internal fun launchHeartbeat(isDevelopment: Boolean, hasLocalSecrets: Boolean = isDevelopment) {
    configureDesktopRendering()
    val classes = checkoutClasses()
    // Launches from the checkout's compiled classes log from DEBUG up; any jar, installed or not, logs as release.
    // `heartbeat.trace` (system property or HEARTBEAT_TRACE) adds the VERBOSE level for deep debugging.
    val sink = initializeDesktopLogging(isDevelopment || classes.getOrNull() != null, isTraceRequested())
    try {
        // Development runs (IDE, Gradle, several worktrees) stay independent; release launches share one process.
        val claim = if (isDevelopment) {
            DesktopInstanceClaim.Unguarded
        } else {
            claimDesktopInstance(desktopInstanceDirectory())
        }
        if (claim == DesktopInstanceClaim.Forwarded) return
        val instance = (claim as? DesktopInstanceClaim.Owner)?.instance
        try {
            launchLoggedHeartbeat(isDevelopment, hasLocalSecrets, classes, instance)
        } finally {
            instance?.close()
        }
    } finally {
        sink?.close()
    }
}

private fun launchLoggedHeartbeat(
    isDevelopment: Boolean,
    hasLocalSecrets: Boolean,
    classes: Result<Path?>,
    instance: DesktopInstance?,
) {
    val log = Log.tag("Desktop")
    classes.onFailure { log.w(it) { "Entry point location is unknown; treating the launch as packaged" } }
    classes.getOrNull()?.let(::attachLocalPiRuntime)
    val applicationIcons = runOnUiThread { loadDesktopIcons().also(::configureDockIcon) }
    val lifecycle = LifecycleRegistry()
    val graph = runOnUiThread { createHeartbeatGraph(isDevelopment, hasLocalSecrets) }
    val root = createDesktopRoot(lifecycle, graph)
    val dimensions = HbDimensions()
    application {
        val systemQuit = remember { DesktopSystemQuit() }
        val shutdown = graph as AppShutdownAccessors
        val quitRequests = rememberDesktopCloseHandler(
            shutdown.shutdownParticipants,
            graph.dispatchers,
            shutdown::closeScopes,
            lifecycle::destroy,
        ) {
            exitApplication()
            systemQuit.complete()
        }
        // Every handler instance shares the same remembered closing state, so the first one is installed once.
        DisposableEffect(Unit) {
            installDesktopQuitHandler(systemQuit, quitRequests.quit)
            onDispose { }
        }
        QuitForAppUpdate(graph.machines, quitRequests.restart)
        val icon = remember(applicationIcons) { applicationIcons.last().toPainter() }
        val windowState = rememberWindowState(width = dimensions.windowWidth, height = dimensions.windowHeight)
        val computerUse by root.computerUse.collectAsState()
        val visibility = rememberDesktopWindowVisibility(
            windowState,
            applicationIcons.last(),
            computerUse.isActive,
            instance,
            quitRequests.quit,
        )
        val plantUml = remember(graph) { (graph as PlantUmlAccessors).plantUml }
        val diagrams = remember(plantUml) { PlantUmlDiagramRenderer(plantUml, graph.dispatchers.default) }
        val areDiagramsAvailable by plantUml.availability.collectAsState(false)
        LifecycleController(lifecycle, windowState)
        var hasModal by remember { mutableStateOf(false) }
        DesktopWindowHost(
            onCloseRequest = visibility::closeWindow,
            isVisible = visibility.isVisible,
            icon = icon,
            state = windowState,
            isAlwaysOnTop = computerUse.isActive,
            onPreviewKeyEvent = { event -> !hasModal && openSettingsOnShortcut(event, root) },
            nativePresentation = { window ->
                RestoreDesktopWindowFocus(window, visibility)
                DesktopComputerUsePresentation(window, windowState, computerUse, graph.capturePresentation)
            },
        ) {
            HeartbeatWindowContent(windowState, applicationIcons, { hasModal = it }) {
                App(root, diagramRenderer = diagrams.takeIf { areDiagramsAvailable })
            }
        }
        DesktopPermissionGuide(graph)
    }
}

/** Creates the root and starts application work once, while the desktop event loop is available. */
private fun createDesktopRoot(lifecycle: LifecycleRegistry, graph: HeartbeatGraph): HeartbeatRoot = runOnUiThread {
    createAppRoot(DefaultComponentContext(lifecycle), graph).also { startAppWork(graph) }
}

@Composable
private fun DesktopPermissionGuide(graph: HeartbeatGraph) {
    val permissionGuide by graph.permissionGuide.guide.collectAsState()
    val guidedPermission = permissionGuide
    if (isMacHost && guidedPermission != null) {
        ComputerUsePermissionGuideWindow(
            guidedPermission,
            graph.dispatchers.io,
            graph.capturePresentation,
            graph.permissionGuide::dismiss,
        )
    }
}

/** One root portal shares the application canvas; the platform owns its popup strategy and native icons. */
@Composable
private fun FrameWindowScope.HeartbeatWindowContent(
    state: WindowState,
    icons: List<BufferedImage>,
    onModalChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    DisposableEffect(window, icons) {
        window.iconImages = icons
        onDispose { }
    }
    HbDesktopPopupRendering(window)
    HbInWindowDialogHost(window, modifier, onActiveChange = onModalChange) {
        DesktopWindowContent(state, content = content)
    }
}

/** Loads optical-size icons for both development launches and installed desktop applications. */
private fun loadDesktopIcons(): List<BufferedImage> = desktopIconSizes.map { size ->
    val directory = if (isMacHost) "/icons/macos" else "/icons"
    val resource = checkNotNull(object {}.javaClass.getResourceAsStream("$directory/heartbeat-$size.png")) {
        "Heartbeat desktop icon ($size px) is missing from application resources"
    }
    resource.use { checkNotNull(ImageIO.read(it)) { "Heartbeat desktop icon ($size px) cannot be decoded" } }
}

private val desktopIconSizes = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)

/** Window icons do not set the macOS Dock icon, so update the taskbar when the host supports it. */
private fun configureDockIcon(icons: List<BufferedImage>) {
    if (!Taskbar.isTaskbarSupported()) return
    val log = Log.tag("Desktop")
    try {
        val taskbar = Taskbar.getTaskbar()
        if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
            taskbar.iconImage = icons.last()
        }
    } catch (e: UnsupportedOperationException) {
        log.w(e) { "Desktop host cannot set the application Dock icon" }
    } catch (e: SecurityException) {
        log.w(e) { "Desktop host denied updating the application Dock icon" }
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
