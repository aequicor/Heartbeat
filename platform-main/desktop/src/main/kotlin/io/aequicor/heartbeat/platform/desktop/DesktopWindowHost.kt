package io.aequicor.heartbeat.platform.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState

/**
 * Keeps native presentation in the application composition, whose frames continue while the window is hidden.
 * Window content retains its own composition and state, but its frame clock stops in the tray. Native indicators
 * must therefore observe activity and release resources here, independently of the hidden content's frame clock.
 */
@Composable
internal fun DesktopWindowHost(
    onCloseRequest: () -> Unit,
    state: WindowState,
    nativePresentation: @Composable (ComposeWindow) -> Unit,
    isVisible: Boolean = true,
    isAlwaysOnTop: Boolean = false,
    isAutoRequestFocus: Boolean = true,
    icon: Painter? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable FrameWindowScope.() -> Unit,
) {
    var nativeWindow by remember { mutableStateOf<ComposeWindow?>(null) }
    Window(
        onCloseRequest = onCloseRequest,
        state = state,
        visible = isVisible,
        title = AppChannel.current.title,
        icon = icon,
        alwaysOnTop = isAlwaysOnTop,
        onPreviewKeyEvent = onPreviewKeyEvent,
    ) {
        val currentWindow = window
        SideEffect { currentWindow.isAutoRequestFocus = isAutoRequestFocus }
        DisposableEffect(currentWindow) {
            nativeWindow = currentWindow
            val shownListener = object : java.awt.event.WindowAdapter() {
                override fun windowOpened(event: java.awt.event.WindowEvent) {
                    // Показано нативное окно (AWT): метка протокола времени запуска (Этап 0).
                    LaunchMarks.mark("app.window-shown")
                }
            }
            currentWindow.addWindowListener(shownListener)
            onDispose {
                currentWindow.removeWindowListener(shownListener)
                if (nativeWindow === currentWindow) nativeWindow = null
            }
        }
        content()
    }
    nativeWindow?.let { nativePresentation(it) }
}
