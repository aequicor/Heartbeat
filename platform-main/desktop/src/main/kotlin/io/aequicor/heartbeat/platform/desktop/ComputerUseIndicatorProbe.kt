package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.graphics.toArgb
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbMotion
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseInputActivity
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseSuppressionReason
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import java.awt.Color
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Window

/** Explicit packaged smoke probe; exercises the shipped native bindings and never injects user input. */
internal fun handleComputerUseIndicatorProbe(arguments: Array<String>): Boolean {
    if (!arguments.contentEquals(arrayOf("--verify-computer-use-indicators"))) return false
    val sink = initializeDesktopLogging(isDebug = true, isTrace = false)
    try {
        EventQueue.invokeAndWait { verifyComputerUseIndicators() }
    } finally {
        sink?.close()
    }
    return true
}

private fun verifyComputerUseIndicators() {
    val colors = HbColors.DesktopLight
    val dimensions = HbDimensions.Desktop
    val motion = HbMotion()
    val monitors = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
        it.defaultConfiguration.bounds
    }
    val shadow = ComputerUseScreenOverlay()
    val pointer = ComputerUsePointerOverlay()
    try {
        shadow.show(
            monitors,
            Color(colors.computerUseShadow.toArgb(), true),
            dimensions.computerUseShadowWidth.value.toInt(),
            OverlayPulse(
                Color(colors.computerUseShadowStart.toArgb(), true),
                Color(colors.computerUseShadowInput.toArgb(), true),
                motion.computerUseStartMillis,
                motion.computerUseInputMillis,
                isDesktopMotionReduced(),
            ),
        )
        val peers = indicatorWindows("overlay")
        check(peers.size == monitors.size && peers.all { it.isVisible }) { "Monitor indicators are not visible" }
        shadow.pulseInput()
        val pause = shadow.pauseForCapture()
        check(peers.none { it.isVisible }) { "Screenshot suppression left an indicator visible" }
        pause.close()
        check(peers.all { it.isVisible }) { "Screenshot suppression did not restore indicators" }
        shadow.hide()
        val area = monitors.first()
        pointer.update(
            ComputerUseInputActivity(
                ScreenBounds(area.x, area.y, area.width, area.height),
                FramePoint(area.width / 2.0, area.height / 2.0),
                isPointerVisible = true,
            ),
            Color(colors.computerUsePointer.toArgb(), true),
            Color(colors.computerUsePointerOutline.toArgb(), true),
            dimensions.computerUsePointerSize.value.toInt(),
            dimensions.computerUsePointerStroke.value,
        )
        val marker = indicatorWindows("pointer").single()
        pointer.suppress(ComputerUseSuppressionReason.PointerInput).use { check(marker.isVisible) }
        pointer.suppress().use { check(!marker.isVisible) }
        check(marker.isVisible)
        Log.tag("ComputerUseIndicatorProbe").i {
            "Native indicators verified monitors=${monitors.joinToString { "${it.width}x${it.height}" }}"
        }
    } finally {
        pointer.close()
        shadow.close()
    }
}

private fun indicatorWindows(kind: String): List<Dialog> = Window.getWindows().filterIsInstance<Dialog>()
    .filter { it.isDisplayable && it.title.startsWith("Heartbeat computer-use $kind ") }
