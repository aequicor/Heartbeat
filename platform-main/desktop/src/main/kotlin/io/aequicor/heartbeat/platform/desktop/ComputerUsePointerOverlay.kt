package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseInputActivity
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseSuppressionReason
import java.awt.BasicStroke
import java.awt.Color
import java.awt.EventQueue
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.util.concurrent.CancellationException
import javax.swing.JDialog
import javax.swing.JPanel
import kotlin.math.roundToInt

/** Independent, non-activating window that follows only acknowledged native agent movements. */
internal class ComputerUsePointerOverlay :
    ComputerUsePresentation,
    AutoCloseable {
    private val log = Log.tag("ComputerUsePointerOverlay")
    private val native by lazy { overlayPassThrough() }
    private var window: JDialog? = null
    private var requested: PointerAppearance? = null
    private var suppressionCount = 0
    private var isClosed = false

    fun update(activity: ComputerUseInputActivity, color: Color, outline: Color, size: Int, stroke: Float) =
        onOverlayThread {
            requested = PointerAppearance(activity, color, outline, size, stroke)
            applyRequested()
        }

    override fun suppress(): AutoCloseable = suppress(ComputerUseSuppressionReason.CapturePixels)

    override fun suppress(reason: ComputerUseSuppressionReason): AutoCloseable {
        check(EventQueue.isDispatchThread())
        if (reason == ComputerUseSuppressionReason.PointerInput) return AutoCloseable { }
        suppressionCount++
        window?.isVisible = false
        var isReleased = false
        return AutoCloseable {
            check(EventQueue.isDispatchThread())
            if (!isReleased) {
                isReleased = true
                suppressionCount--
                applyRequested()
            }
        }
    }

    override fun close() = onOverlayThread {
        isClosed = true
        window?.dispose()
        window = null
    }

    private fun applyRequested() {
        if (isClosed || suppressionCount > 0) return
        val next = requested ?: return
        val bounds = next.activity.bounds
        val point = next.activity.pointer
        if (!next.activity.isPointerVisible || bounds == null || point == null) {
            window?.isVisible = false
            return
        }
        try {
            val peer = window ?: newOverlayWindow("pointer").also { window = it }
            peer.contentPane = AgentPointer(next.color, next.outline, next.stroke)
            peer.setBounds(bounds.x + point.x.roundToInt(), bounds.y + point.y.roundToInt(), next.size, next.size)
            peer.validate()
            if (!peer.isVisible) showOverlayWindow(peer, native) else peer.repaint()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Cannot show agent pointer" }
            window?.dispose()
            window = null
        } catch (e: LinkageError) {
            log.w(e) { "Native agent pointer is unavailable" }
            window?.dispose()
            window = null
        }
    }
}

private data class PointerAppearance(
    val activity: ComputerUseInputActivity,
    val color: Color,
    val outline: Color,
    val size: Int,
    val stroke: Float,
)

/** Token-colored arrow with a contrasting outline; the upper-left tip is the acknowledged pointer point. */
internal class AgentPointer(private val fill: Color, private val outline: Color, private val stroke: Float) : JPanel() {
    init {
        isOpaque = false
        isFocusable = false
    }

    override fun paintComponent(graphics: Graphics) {
        val canvas = graphics.create() as Graphics2D
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val side = minOf(width, height).toDouble() - stroke
            canvas.translate(stroke / 2.0, stroke / 2.0)
            val arrow = Path2D.Double().apply {
                moveTo(0.0, 0.0)
                lineTo(side * 0.78, side * 0.55)
                lineTo(side * 0.47, side * 0.59)
                lineTo(side * 0.64, side * 0.91)
                lineTo(side * 0.45, side)
                lineTo(side * 0.29, side * 0.67)
                lineTo(0.0, side * 0.89)
                closePath()
            }
            canvas.color = fill
            canvas.fill(arrow)
            canvas.color = outline
            canvas.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            canvas.draw(arrow)
        } finally {
            canvas.dispose()
        }
    }
}
