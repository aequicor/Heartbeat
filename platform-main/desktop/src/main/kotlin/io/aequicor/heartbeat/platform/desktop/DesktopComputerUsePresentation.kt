package io.aequicor.heartbeat.platform.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapturePresentation
import io.aequicor.heartbeat.platform.dibundle.root.ComputerUseActivity
import java.awt.Color
import java.awt.Rectangle
import java.awt.Toolkit
import javax.swing.JFrame

/** Native presentation belongs to the window and is removed even when the profile/root is destroyed. */
@Composable
internal fun DesktopComputerUsePresentation(
    window: JFrame,
    state: WindowState,
    activity: ComputerUseActivity,
    capturePresentation: ComputerUseCapturePresentation,
) {
    val overlay = remember(window) { ComputerUseScreenOverlay(window) }
    val presentation = remember(window, overlay) { DesktopCapturePresentation(window, overlay) }
    val dimensions = HbDimensions.Desktop
    val color = HbColors.forHost(isSystemInDarkTheme(), isDesktop = true).computerUseShadow.toArgb()
    DisposableEffect(presentation, capturePresentation) {
        val registration = capturePresentation.register(presentation)
        onDispose {
            presentation.close()
            registration.close()
        }
    }
    DisposableEffect(window, state, activity.isActive) {
        val saved = if (activity.isActive) DesktopWindowSnapshot(state) else null
        if (saved != null) {
            val configuration = window.graphicsConfiguration
            val workArea = Rectangle(configuration.bounds)
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
            workArea.x += insets.left
            workArea.y += insets.top
            workArea.width -= insets.left + insets.right
            workArea.height -= insets.top + insets.bottom
            val target = computerUseSessionBounds(
                workArea,
                dimensions.computerUseSessionWidth.value.toInt(),
                dimensions.windowHeight.value.toInt(),
            )
            state.placement = WindowPlacement.Floating
            state.isMinimized = false
            state.size = DpSize(Dp(target.width.toFloat()), Dp(target.height.toFloat()))
            state.position = WindowPosition.Absolute(Dp(target.x.toFloat()), Dp(target.y.toFloat()))
            Log.tag("DesktopComputerUse").i { "agent capture: session pinned to screen edge" }
        }
        onDispose {
            saved?.restore(state)
            if (saved != null) Log.tag("DesktopComputerUse").i { "agent capture ended: window restored" }
        }
    }
    DisposableEffect(overlay, activity.screens, color) {
        if (activity.screens.isEmpty()) {
            overlay.hide()
        } else {
            overlay.show(
                activity.screens.map { Rectangle(it.x, it.y, it.width, it.height) },
                Color(color, true),
                dimensions.computerUseShadowWidth.value.toInt(),
            )
        }
        onDispose { overlay.hide() }
    }
    DisposableEffect(overlay) { onDispose { overlay.close() } }
}

/** Keeps the session inside the usable screen, including monitors left or above the primary one. */
internal fun computerUseSessionBounds(workArea: Rectangle, width: Int, height: Int): Rectangle {
    val boundedWidth = width.coerceIn(1, workArea.width.coerceAtLeast(1))
    val boundedHeight = height.coerceIn(1, workArea.height.coerceAtLeast(1))
    return Rectangle(
        workArea.x + workArea.width - boundedWidth,
        workArea.y + (workArea.height - boundedHeight) / 2,
        boundedWidth,
        boundedHeight,
    )
}

/** Placement is restored last so maximized/fullscreen windows recover their original floating bounds too. */
internal class DesktopWindowSnapshot(state: WindowState) {
    private val placement = state.placement
    private val position = state.position
    private val size = state.size
    private val isMinimized = state.isMinimized

    fun restore(state: WindowState) {
        state.size = size
        state.position = position
        state.placement = placement
        state.isMinimized = isMinimized
    }
}
