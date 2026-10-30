// The numeric message IDs and caption bounds form the explicit host.cpp JNI/Win32 ABI.
@file:Suppress("MagicNumber")

package io.aequicor.heartbeat.platform.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import io.aequicor.heartbeat.ds.components.HbDesktopPopupRendering
import io.aequicor.heartbeat.ds.components.HbInWindowDialogHost
import io.aequicor.heartbeat.ds.components.HbWindowChrome
import io.aequicor.heartbeat.ds.components.HbWindowChromeProvider
import io.aequicor.heartbeat.ds.components.HbWindowStartupProvider
import io.aequicor.heartbeat.ds.components.HbWindowStartupState
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.platform.dibundle.HeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.PlantUmlAccessors
import io.aequicor.heartbeat.platform.dibundle.createHeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.desktopStorageRoot
import io.aequicor.heartbeat.platform.shared.App
import io.aequicor.heartbeat.platform.shared.createAppRoot
import java.awt.BorderLayout
import java.awt.Color
import java.awt.EventQueue
import java.awt.Frame
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JRootPane

/** Real graph/root/AI Studio within the JAWT Frame; all storage is isolated by the probe's data-name property. */
object EmbeddedStudioProbe {
    private const val SESSION = "heartbeat.embedded.probe.session"

    @JvmStatic
    fun reveal(frame: Frame, now: Long) {
        EventQueue.invokeAndWait {
            val rootPane = frame.components.filterIsInstance<JRootPane>().single()
            (rootPane.getClientProperty(SESSION) as Session).revealedAt = now
        }
    }

    @JvmStatic
    fun mount(frame: Frame, host: Long) {
        configureDesktopRendering()
        val sink = initializeDesktopLogging(true, false)
        var lease: DesktopStorageOwner? = null
        var session: Session? = null
        var isMounted = false
        try {
            lease = checkNotNull(DesktopStorageOwner.claim(Path.of(desktopStorageRoot())))
            EventQueue.invokeAndWait {
                val created = Session(frame, host, lease, sink)
                session = created
                created.attach()
                frame.components.filterIsInstance<JRootPane>().single().putClientProperty(SESSION, created)
            }
            isMounted = true
        } finally {
            if (!isMounted) {
                // JNI invocation thread, not EDT: shutdown needs the UI dispatcher to remain isAvailable.
                val created = session
                if (created != null) {
                    created.close()
                } else {
                    try {
                        lease?.close()
                    } finally {
                        sink?.close()
                    }
                }
            }
        }
    }

    @JvmStatic
    fun unmount(frame: Frame) {
        val rootPane = frame.components.filterIsInstance<JRootPane>().single()
        (rootPane.getClientProperty(SESSION) as Session).close()
    }

    private class Session(
        private val frame: Frame,
        host: Long,
        private val lease: DesktopStorageOwner,
        private val sink: AutoCloseable?,
    ) : AutoCloseable {
        var revealedAt by mutableStateOf<Long?>(null)
        private val lifecycle = LifecycleRegistry()
        private val graph: HeartbeatGraph = createHeartbeatGraph(isDevelopment = true, hasLocalSecrets = true)
        private val panel = ComposePanel()
        private val nativeHost = HWND(Pointer(host))
        private fun captionHit(client: Boolean) {
            User32.INSTANCE.PostMessage(nativeHost, 0x800E, WPARAM(if (client) 1 else 0), LPARAM(0))
        }

        fun attach() {
            val isDark = System.getProperty("heartbeat.embedded.dark", "true").toBoolean()
            val background = if (isDark) Color(32, 32, 32) else Color.WHITE
            val rootPane = JRootPane()
            rootPane.contentPane.background = background
            rootPane.contentPane.add(panel, BorderLayout.CENTER)
            frame.background = background
            frame.layout = BorderLayout()
            frame.add(rootPane, BorderLayout.CENTER)
            val root = createAppRoot(DefaultComponentContext(lifecycle), graph)
            val startup = HbWindowStartupState()
            val ready = AtomicBoolean(false)
            val plantUml = (graph as PlantUmlAccessors).plantUml
            val diagrams = PlantUmlDiagramRenderer(plantUml, graph.dispatchers.default)
            startAppWork(graph)
            lifecycle.resume()
            panel.setContent {
                val isFocused = LocalWindowInfo.current.isWindowFocused
                SideEffect { graph.windowFocus.update(isFocused) }
                val isAvailable by plantUml.availability.collectAsState(false)
                HbDesktopPopupRendering(frame)
                HbInWindowDialogHost(
                    frame,
                    modifier = Modifier.drawWithContent {
                        drawContent()
                        if (startup.isReady && ready.compareAndSet(false, true)) {
                            notifyStudioReady()
                        }
                    },
                    rootPane = rootPane,
                ) {
                    HbWindowStartupProvider(startup) {
                        HbWindowChromeProvider(
                            HbWindowChrome(height = HbDimensions.Desktop.headerHeight, rightInset = 144.dp),
                            onNativeHitTest = ::captionHit,
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                App(root, diagramRenderer = diagrams.takeIf { isAvailable }, darkTheme = isDark)
                                HbTheme(
                                    darkTheme = isDark,
                                    platformUi = PlatformUi.Fluent,
                                    dimensions = HbDimensions.Desktop,
                                ) {
                                    EmbeddedIntroOverlay(isDark, revealedAt)
                                }
                            }
                        }
                    }
                }
            }
            frame.validate()
            frame.isVisible = true
            User32.INSTANCE.PostMessage(
                nativeHost,
                0x800C,
                WPARAM(0),
                LPARAM(Pointer.nativeValue(Native.getWindowPointer(frame))),
            )
        }

        private fun notifyStudioReady() {
            EventQueue.invokeLater {
                panel.paintImmediately(0, 0, panel.width, panel.height)
                java.awt.Toolkit.getDefaultToolkit().sync()
                WindowsHandoffPresentation.flush()
                User32.INSTANCE.PostMessage(nativeHost, 0x8007, WPARAM(0), LPARAM(0))
            }
        }

        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        override fun close() {
            try {
                shutdownFailedDesktopLaunch(graph) {
                    lifecycle.destroy()
                    panel.dispose()
                    frame.dispose()
                }
            } finally {
                lease.close()
                sink?.close()
            }
        }
    }
}
