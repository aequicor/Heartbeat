package io.aequicor.heartbeat.platform.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseActivity
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapturePresentation
import kotlinx.coroutines.delay
import java.awt.Color
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.time.Duration.Companion.milliseconds

/** Native presentation belongs to the window and is removed even when the profile/root is destroyed. */
@Composable
internal fun DesktopComputerUsePresentation(
    window: ComposeWindow,
    state: WindowState,
    activity: ComputerUseActivity,
    capturePresentation: ComputerUseCapturePresentation,
) {
    val overlay = remember(window) { ComputerUseScreenOverlay(window) }
    val presentation = remember(window, overlay) { DesktopCapturePresentation(window, overlay) }
    val dock = remember(window, state) { ComputerUseWindowDock(ComposeDockableWindow(window, state)) }
    val dimensions = HbDimensions.Desktop
    val color = HbColors.forHost(isSystemInDarkTheme(), isDesktop = true).computerUseShadow.toArgb()
    DisposableEffect(presentation, capturePresentation) {
        val registration = capturePresentation.register(presentation)
        onDispose {
            presentation.close()
            registration.close()
        }
    }
    // Restoration outlives this effect: the dock finishes it even when the activity ends or the window leaves.
    LaunchedEffect(dock, activity.isActive) {
        if (activity.isActive) {
            dock.holdPinned(
                width = dimensions.computerUseSessionWidth.value.toInt(),
                height = dimensions.windowHeight.value.toInt(),
            )
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
        // show() recolors in place and recreates windows only for a new monitor layout; close() below disposes them.
        onDispose { }
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

/** Geometry comes from the Compose window itself; Compose applies [state] on the event thread. */
private class ComposeDockableWindow(private val window: ComposeWindow, override val state: WindowState) :
    DockableWindow {
    override val isAlive: Boolean get() = window.isDisplayable
    override val placement: WindowPlacement get() = window.placement
    override val isMinimized: Boolean get() = window.isMinimized
    override val bounds: Rectangle get() = window.bounds

    override val workArea: Rectangle
        get() {
            val configuration = window.graphicsConfiguration
            val area = Rectangle(configuration.bounds)
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
            area.x += insets.left
            area.y += insets.top
            area.width -= insets.left + insets.right
            area.height -= insets.top + insets.bottom
            return area
        }

    // Leaving fullscreen has no AWT event of its own (Compose infers it from resizes), so the window is polled.
    override suspend fun awaitToolkit() = delay(TOOLKIT_POLL_INTERVAL)

    private companion object {
        val TOOLKIT_POLL_INTERVAL = 16.milliseconds
    }
}
