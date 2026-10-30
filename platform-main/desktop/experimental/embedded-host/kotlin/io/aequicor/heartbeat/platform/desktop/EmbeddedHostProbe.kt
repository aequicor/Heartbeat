// The message ID and radix are part of the minimal JNI/Win32 diagnostics probe.
@file:Suppress("MagicNumber")

package io.aequicor.heartbeat.platform.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.draw.drawWithContent
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import java.awt.BorderLayout
import java.awt.EventQueue
import java.awt.Frame
import java.util.concurrent.atomic.AtomicBoolean

/** Isolated ABI probe: no ComposeWindow/JFrame is created; the supplied Frame is owned by JAWT. */
object EmbeddedHostProbe {
    @JvmStatic
    fun reveal(frame: Frame, now: Long) {
        if (System.getProperty("heartbeat.embedded.content") == "studio") EmbeddedStudioProbe.reveal(frame, now)
    }

    @JvmStatic
    fun present(frame: Frame) {
        EventQueue.invokeAndWait {
            frame.components.filterIsInstance<javax.swing.JComponent>().forEach {
                it.paintImmediately(0, 0, it.width, it.height)
            }
            java.awt.Toolkit.getDefaultToolkit().sync()
            WindowsHandoffPresentation.flush()
        }
    }

    @JvmStatic
    fun mount(frame: Frame, host: Long) {
        if (System.getProperty("heartbeat.embedded.content") == "studio") {
            EmbeddedStudioProbe.mount(frame, host)
            return
        }
        EventQueue.invokeAndWait {
            val ready = AtomicBoolean(false)
            val panel = ComposePanel()
            frame.layout = BorderLayout()
            frame.add(panel, BorderLayout.CENTER)
            panel.setContent {
                HbTheme(darkTheme = true, platformUi = PlatformUi.Fluent, dimensions = HbDimensions.Desktop) {
                    var text by remember { mutableStateOf("") }
                    var clicks by remember { mutableIntStateOf(0) }
                    Column(
                        Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).padding(HbTheme.spacing.xxl)
                            .drawWithContent {
                                drawContent()
                                if (ready.compareAndSet(false, true)) {
                                    EventQueue.invokeLater {
                                        User32.INSTANCE.PostMessage(HWND(Pointer(host)), 0x8007, WPARAM(0), LPARAM(0))
                                    }
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(HbTheme.spacing.xl),
                    ) {
                        HbText("ComposePanel inside the original native HWND", style = HbTheme.typography.title)
                        HbText("JNI + JAWT API 9 · input / Tab / resize probe")
                        HbTextField(
                            text,
                            { text = it },
                            Modifier.fillMaxWidth(),
                            placeholder = "Введите русский текст / IME",
                        )
                        HbButton("Clicks: $clicks", { clicks++ })
                        HbText("Native host: 0x${host.toString(16)}")
                    }
                }
            }
            frame.validate()
            frame.isVisible = true
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @JvmStatic
    fun unmount(frame: Frame) {
        if (System.getProperty("heartbeat.embedded.content") == "studio") {
            EmbeddedStudioProbe.unmount(frame)
            return
        }
        EventQueue.invokeAndWait {
            frame.components.filterIsInstance<ComposePanel>().forEach { it.dispose() }
            frame.dispose()
        }
    }
}
