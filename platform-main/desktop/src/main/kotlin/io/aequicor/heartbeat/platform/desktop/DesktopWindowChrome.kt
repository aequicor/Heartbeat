package io.aequicor.heartbeat.platform.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbWindowChrome
import io.aequicor.heartbeat.ds.components.HbWindowDragProvider
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbSurfaceColors
import java.awt.Color
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.JFrame

/** Owns native decoration for one window; no native handles or JBR types escape into common UI. */
@Composable
internal fun FrameWindowScope.DesktopWindowContent(state: WindowState, content: @Composable () -> Unit) {
    val controller = remember(window) { DesktopWindowChrome(window) }
    var chrome by remember { mutableStateOf(HbWindowChrome()) }
    var revision by remember { mutableIntStateOf(0) }
    val isDark = isSystemInDarkTheme()
    val isActive = LocalWindowInfo.current.isWindowFocused
    DisposableEffect(controller, window) {
        val changes = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                revision++
            }
            override fun componentMoved(e: ComponentEvent) {
                revision++
            }
        }
        val scaleChanges = PropertyChangeListener { revision++ }
        val clicks = object : MouseAdapter() {
            // Compose translates press/release/move, but not AWT's subsequent MOUSE_CLICKED event.
            override fun mouseClicked(e: MouseEvent) {
                controller.repeatHitTest()
            }
        }
        window.addComponentListener(changes)
        window.addMouseListener(clicks)
        window.addPropertyChangeListener("graphicsConfiguration", scaleChanges)
        onDispose {
            window.removeComponentListener(changes)
            window.removeMouseListener(clicks)
            window.removePropertyChangeListener("graphicsConfiguration", scaleChanges)
            controller.dispose()
        }
    }
    SideEffect(controller, revision, state.placement, isDark, isActive) {
        chrome = controller.configure(isDark, isActive, state.placement == WindowPlacement.Fullscreen)
    }
    val hitTest = remember(controller, chrome.height) {
        if (controller.hasNativeCaption) controller::hitTest else null
    }
    HbWindowDragProvider(chrome, hitTest, content)
}

/**
 * JBR uses AWT logical pixels for its caption metrics, matching Compose dp at the host scale.
 * A normal JDK retains macOS full-window content or Windows decorations as a development fallback.
 */
internal class DesktopWindowChrome(private val window: JFrame) {
    private val log = Log.tag("DesktopWindowChrome")
    private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val decorations = if (isMac || isWindows) JBR.getWindowDecorations() else null
    private var titleBar: WindowDecorations.CustomTitleBar? = null
    private var isConfigured = false
    private var isClientArea = true

    val hasNativeCaption: Boolean get() = titleBar != null

    fun configure(isDark: Boolean, isActive: Boolean, fullscreen: Boolean): HbWindowChrome {
        val dimensions = HbDimensions.Desktop
        if (!isConfigured) {
            isConfigured = true
            if (decorations != null) {
                titleBar = decorations.createCustomTitleBar().apply { height = dimensions.headerHeight.value }
                decorations.setCustomTitleBar(window, titleBar)
                log.i { "JBR native caption enabled" }
            } else if (isMac) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                log.w { "JBR caption unavailable; using macOS full-window content" }
            } else if (isWindows) {
                log.w { "JBR caption unavailable; using standard Windows decoration" }
            }
        }
        val background = Color(HbSurfaceColors.forHost(isDark, isDesktop = true).backdrop.toArgb(), true)
        window.background = background
        window.contentPane.background = background
        titleBar?.putProperty("controls.dark", isDark)
        return HbWindowChrome(
            height = if (hasNativeCaption || isMac) dimensions.headerHeight else HbDimensions.Desktop.titlebarInset,
            leftInset = Dp(titleBar?.leftInset ?: if (isMac) dimensions.titlebarLeadingInset.value else 0f),
            rightInset = Dp(titleBar?.rightInset ?: 0f),
            isFullscreen = fullscreen,
            isActive = isActive,
        )
    }

    fun hitTest(client: Boolean) {
        isClientArea = client
        titleBar?.forceHitTest(client)
    }

    fun repeatHitTest() {
        titleBar?.forceHitTest(isClientArea)
    }

    fun dispose() {
        if (titleBar != null) decorations?.setCustomTitleBar(window, null)
        titleBar = null
    }
}
