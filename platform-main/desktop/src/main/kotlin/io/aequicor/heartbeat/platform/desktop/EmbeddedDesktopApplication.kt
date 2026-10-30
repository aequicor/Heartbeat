package io.aequicor.heartbeat.platform.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbDesktopPopupRendering
import io.aequicor.heartbeat.ds.components.HbInWindowDialogHost
import io.aequicor.heartbeat.ds.components.HbWindowChrome
import io.aequicor.heartbeat.ds.components.HbWindowChromeProvider
import io.aequicor.heartbeat.ds.components.HbWindowStartupProvider
import io.aequicor.heartbeat.ds.components.HbWindowStartupState
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.platform.dibundle.HeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.PlantUmlAccessors
import java.awt.BorderLayout
import java.awt.EventQueue
import java.awt.Frame
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JRootPane
import javax.swing.Timer

/** Runs inside the selector's blocking main invocation, retaining its slot/train and storage ownership until quit. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun launchEmbeddedDesktopApplication(
    host: EmbeddedHostConnection,
    lifecycle: LifecycleRegistry,
    graph: HeartbeatGraph,
    icons: List<BufferedImage>,
    instance: DesktopInstance?,
): Boolean {
    val frame = runOnUiThread { host.createFrame() } ?: return false
    val session = EmbeddedDesktopSession(host, lifecycle, graph, icons, instance, frame)
    try {
        runOnUiThread { session.attach() }
        session.awaitExit()
    } finally {
        runOnUiThread { session.close() }
    }
    return true
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private class EmbeddedDesktopSession(
    private val host: EmbeddedHostConnection,
    private val lifecycle: LifecycleRegistry,
    private val graph: HeartbeatGraph,
    private val icons: List<BufferedImage>,
    private val instance: DesktopInstance?,
    private val frame: Frame,
) : AutoCloseable {
    private val log = Log.tag("EmbeddedHost")
    private val stopped = CountDownLatch(1)
    private val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    private val root by lazy { createDesktopRoot(lifecycle, graph) }
    private val nativeWindow = HWND(Pointer(host.handle))
    private val isIconic = com.sun.jna.NativeLibrary.getInstance("user32").getFunction("IsIconic")
    private var panel: ComposePanel? = null
    private var timer: Timer? = null
    private var revealedAt by mutableStateOf<Long?>(null)
    private var onCloseRequest: (() -> Unit)? = null
    private var onMinimizedChange: ((Boolean) -> Unit)? = null
    private var isIntroVisible by mutableStateOf(true)
    private var isCaptureSuppressed by mutableStateOf(false)
    private var lastRestoreRequest = 0

    fun attach() {
        val window = frame
        root // Start application work on EDT, before constructing the composition.
        val content = ComposePanel().also { panel = it }
        content.exceptionHandler = androidx.compose.ui.window.WindowExceptionHandler { error ->
            fail(error)
            if (error is kotlinx.coroutines.CancellationException) throw error
            log.e(error) { "Embedded composition failed" }
        }
        val rootPane = JRootPane()
        window.layout = BorderLayout()
        window.add(rootPane, BorderLayout.CENTER)
        rootPane.contentPane.add(content, BorderLayout.CENTER)
        window.iconImages = icons
        host.attachChild(Pointer.nativeValue(com.sun.jna.Native.getWindowPointer(window)))
        window.cursor = java.awt.Cursor.getDefaultCursor()
        host.synchronize(window)
        content.setContent { Content(window, content, rootPane) }
        window.validate()
        window.isVisible = true
        timer = Timer(EMBEDDED_POLL_MILLIS) { synchronize(window) }.also { it.start() }
    }

    @HighFrequency
    private fun synchronize(window: Frame) {
        try {
            host.synchronize(window)
            onMinimizedChange?.invoke(isNativeMinimized())
            if (revealedAt == null && host.isRevealed()) {
                val now = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetTickCount64()
                log.v { "revealedAt: null -> $now" }
                revealedAt = now
            }
            onCloseRequest?.let { if (host.takeCloseRequest()) it() }
        } catch (error: kotlinx.coroutines.CancellationException) {
            fail(error)
            throw error
        } catch (error: Exception) {
            log.e(error) { "Embedded window update failed" }
            fail(error)
        } catch (error: LinkageError) {
            log.e(error) { "Embedded host ABI failed" }
            fail(error)
        }
    }

    private fun fail(error: Throwable) {
        // Re-throw on the blocking application thread so Main preserves the failed-launch/rollback path.
        failure.compareAndSet(null, error)
        timer?.stop()
        stopped.countDown()
    }

    private fun present(content: ComposePanel, window: Frame) {
        try {
            presentEmbeddedFrame(content, window, host)
        } catch (error: kotlinx.coroutines.CancellationException) {
            fail(error)
            throw error
        } catch (error: Exception) {
            log.e(error) { "Cannot present embedded content" }
            fail(error)
        } catch (error: LinkageError) {
            log.e(error) { "Embedded presentation ABI failed" }
            fail(error)
        }
    }

    fun awaitExit() {
        stopped.await()
        failure.get()?.let { throw it }
    }

    @androidx.compose.runtime.Composable
    private fun Content(window: Frame, content: ComposePanel, rootPane: JRootPane) {
        val quit = rememberApplicationQuit(graph, lifecycle, stopped::countDown)
        val state = rememberWindowState()
        val lifecycleState = rememberWindowState()
        LifecycleController(lifecycle, lifecycleState)
        val computerUse by root.computerUse.collectAsState()
        val visibility = rememberDesktopWindowVisibility(
            state,
            icons.last(),
            computerUse.isActive,
            instance,
            quit.quit,
        )
        val isFocused = LocalWindowInfo.current.isWindowFocused || computerUse.isActive
        SideEffect {
            onCloseRequest = visibility::closeWindow
            onMinimizedChange = { lifecycleState.isMinimized = it }
            graph.windowFocus.update(isFocused)
        }
        DisposableEffect(visibility.isVisible, visibility.restoreRequest, isCaptureSuppressed) {
            if (!visibility.isVisible) {
                User32.INSTANCE.ShowWindow(nativeWindow, WinUser.SW_HIDE)
            } else if (shouldOpenEmbeddedWindow(isCaptureSuppressed, visibility.restoreRequest, lastRestoreRequest)) {
                val command = embeddedOpenCommand(isNativeMinimized())
                User32.INSTANCE.ShowWindow(nativeWindow, command)
                User32.INSTANCE.SetForegroundWindow(nativeWindow)
                lastRestoreRequest = visibility.restoreRequest
            }
            onDispose { }
        }
        DisposableEffect(computerUse.isActive) {
            setEmbeddedTopmost(nativeWindow, computerUse.isActive)
            onDispose { }
        }
        val dockable = remember(window, state) { EmbeddedDockableWindow(nativeWindow, state) }
        DesktopWindowComputerUsePresentation(
            window,
            computerUse,
            graph.capturePresentation,
            dockable,
            isWindowVisible = { visibility.isVisible },
            onSuppressionChange = { isCaptureSuppressed = it },
        )
        Studio(window, content, rootPane)
    }

    private fun isNativeMinimized(): Boolean = isIconic.invokeInt(arrayOf(nativeWindow)) != 0

    @androidx.compose.runtime.Composable
    private fun Studio(window: Frame, content: ComposePanel, rootPane: JRootPane) {
        val plantUml = remember(graph) { (graph as PlantUmlAccessors).plantUml }
        val diagrams = remember(plantUml) { PlantUmlDiagramRenderer(plantUml, graph.dispatchers.default) }
        val isAvailable by plantUml.availability.collectAsState(false)
        val startup = remember { HbWindowStartupState() }
        val ready = remember { AtomicBoolean(false) }
        var hasModal by remember { mutableStateOf(false) }
        HbDesktopPopupRendering(window)
        HbInWindowDialogHost(
            window,
            modifier = Modifier.onPreviewKeyEvent { event ->
                embeddedConsumesKey(isIntroVisible, hasModal) { openSettingsOnShortcut(event, root) }
            }.drawWithContent {
                drawContent()
                if (startup.isReady && ready.compareAndSet(false, true)) {
                    EventQueue.invokeLater { present(content, window) }
                }
            },
            rootPane = rootPane,
            onActiveChange = { hasModal = it },
        ) {
            HbWindowStartupProvider(startup) {
                HbWindowChromeProvider(
                    HbWindowChrome(
                        height = HbDimensions.Desktop.headerHeight,
                        rightInset = HbDimensions.Desktop.nativeCaptionControlsWidth,
                    ),
                    onNativeHitTest = host::captionClient,
                ) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        val isDark = launcherDarkThemeOverride() ?: androidx.compose.foundation.isSystemInDarkTheme()
                        HeartbeatApp(root, diagrams, isAvailable, isDark)
                        io.aequicor.heartbeat.ds.theme.HbTheme(
                            darkTheme = isDark,
                            platformUi = io.aequicor.heartbeat.ds.adaptive.PlatformUi.Fluent,
                            dimensions = HbDimensions.Desktop,
                        ) {
                            EmbeddedIntroOverlay(revealedAt, onVisibleChange = { isIntroVisible = it })
                        }
                    }
                }
            }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun close() {
        timer?.stop()
        try {
            panel?.dispose()
        } finally {
            frame.dispose()
        }
    }
}

private fun presentEmbeddedFrame(panel: ComposePanel, frame: Frame, host: EmbeddedHostConnection) {
    if (!frame.isDisplayable) return
    panel.paintImmediately(0, 0, panel.width, panel.height)
    java.awt.Toolkit.getDefaultToolkit().sync()
    WindowsHandoffPresentation.flush()
    host.present()
    LaunchMarks.mark("app.first-frame")
}

private const val EMBEDDED_POLL_MILLIS = 16

/** An already maximized window must stay maximized when a repeated launch activates it. */
internal fun embeddedOpenCommand(isMinimized: Boolean): Int = if (isMinimized) WinUser.SW_RESTORE else WinUser.SW_SHOWNA

internal fun shouldOpenEmbeddedWindow(isCaptureSuppressed: Boolean, request: Int, applied: Int): Boolean =
    !isCaptureSuppressed && request > applied

internal inline fun embeddedConsumesKey(
    isIntroVisible: Boolean,
    hasModal: Boolean,
    onShortcut: () -> Boolean,
): Boolean = isIntroVisible || (!hasModal && onShortcut())

private fun setEmbeddedTopmost(window: HWND, active: Boolean) {
    val order = HWND(Pointer(if (active) WINDOWS_TOPMOST else WINDOWS_NOT_TOPMOST))
    check(
        User32.INSTANCE.SetWindowPos(
            window,
            order,
            0,
            0,
            0,
            0,
            WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOACTIVATE,
        ),
    ) { "Cannot update embedded window stacking" }
}

private const val WINDOWS_TOPMOST = -1L
private const val WINDOWS_NOT_TOPMOST = -2L
