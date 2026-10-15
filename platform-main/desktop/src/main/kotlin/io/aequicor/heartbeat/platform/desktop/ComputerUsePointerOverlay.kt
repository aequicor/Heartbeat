package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseInputActivity
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseSuppressionReason
import java.awt.BasicStroke
import java.awt.Color
import java.awt.EventQueue
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.CancellationException
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.roundToInt

/** Token values of the agent cursor, resolved by the host from the design system. */
internal data class AgentCursorStyle(
    val fill: Color,
    val outline: Color,
    val ripple: Color,
    val badge: Color,
    val badgeContent: Color,
    val pointerSize: Int,
    val stroke: Float,
    val canvasSize: Int,
    val rippleRadius: Int,
    val trailWidth: Float,
    val badgeTextSize: Int,
    val isBadgeBold: Boolean,
    val badgePadding: Int,
    val badgeOffset: Int,
    val timing: CursorTiming,
)

/**
 * Independent, non-activating window that follows only acknowledged native agent movements: the tip eases between
 * pointer steps, and clicks, drags, wheel notches and keystrokes leave a short-lived mark. A frame timer runs only
 * while something moves or fades.
 */
internal class ComputerUsePointerOverlay(private val clock: () -> Long = ::monotonicMillis) :
    ComputerUsePresentation,
    AutoCloseable {
    private val log = Log.tag("ComputerUsePointerOverlay")
    private val native by lazy { overlayPassThrough() }
    private var window: JDialog? = null
    private var canvas: AgentCursorCanvas? = null
    private var animator: AgentCursorAnimator? = null
    private var style: AgentCursorStyle? = null
    private var activity: ComputerUseInputActivity? = null
    private var suppressionCount = 0
    private var isClosed = false
    private val frames = Timer(FRAME_MILLIS) { renderFrame() }

    fun update(activity: ComputerUseInputActivity, style: AgentCursorStyle) = onOverlayThread {
        if (this.style != style) {
            this.style = style
            animator = AgentCursorAnimator(style.timing)
            canvas?.style = style
        }
        this.activity = activity
        val bounds = activity.bounds
        val point = activity.pointer
        if (activity.isPointerVisible && bounds != null && point != null) {
            checkNotNull(animator).update(
                CursorPoint(bounds.x + point.x, bounds.y + point.y),
                activity.gesture,
                activity.effectSequence,
                clock(),
            )
        } else {
            animator?.reset()
        }
        renderFrame()
    }

    override fun suppress(): AutoCloseable = suppress(ComputerUseSuppressionReason.CapturePixels)

    override fun suppress(reason: ComputerUseSuppressionReason): AutoCloseable {
        check(EventQueue.isDispatchThread())
        if (reason == ComputerUseSuppressionReason.PointerInput) return AutoCloseable { }
        suppressionCount++
        window?.isVisible = false
        frames.stop()
        var isReleased = false
        return AutoCloseable {
            check(EventQueue.isDispatchThread())
            if (!isReleased) {
                isReleased = true
                suppressionCount--
                renderFrame()
            }
        }
    }

    override fun close() = onOverlayThread {
        isClosed = true
        frames.stop()
        window?.dispose()
        window = null
        canvas = null
    }

    /** Runs on every overlay frame and every pointer step; it never logs on success. */
    @HighFrequency
    private fun renderFrame() {
        if (isClosed || suppressionCount > 0) return
        val currentStyle = style ?: return
        val scene = animator?.scene(clock())
        if (scene == null || activity?.isPointerVisible != true) {
            window?.isVisible = false
            frames.stop()
            return
        }
        try {
            show(scene, currentStyle)
            if (scene.isAnimating) frames.start() else frames.stop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Cannot show agent pointer" }
            dispose()
        } catch (e: LinkageError) {
            log.w(e) { "Native agent pointer is unavailable" }
            dispose()
        }
    }

    private fun show(scene: CursorScene, style: AgentCursorStyle) {
        val peer = window ?: newOverlayWindow("pointer").also { window = it }
        val panel = canvas ?: AgentCursorCanvas(style).also {
            canvas = it
            peer.contentPane = it
        }
        val origin = canvasOrigin(scene.tip, style.canvasSize, screenContaining(scene.tip))
        panel.scene = scene
        panel.origin = origin
        peer.setBounds(origin.x, origin.y, style.canvasSize, style.canvasSize)
        if (!peer.isVisible) {
            peer.validate()
            showOverlayWindow(peer, native)
        } else {
            panel.repaint()
        }
    }

    private fun dispose() {
        frames.stop()
        window?.dispose()
        window = null
        canvas = null
    }

    private companion object {
        const val FRAME_MILLIS = 16
    }
}

/** Milliseconds of a clock that never jumps, unlike the wall clock. */
private fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

private const val NANOS_PER_MILLI = 1_000_000L

/** The canvas is centered on the tip but kept on the screen that holds it, so effects are not cut at an edge. */
internal fun canvasOrigin(tip: CursorPoint, canvasSize: Int, screen: Rectangle?): java.awt.Point {
    val x = tip.x.roundToInt() - canvasSize / 2
    val y = tip.y.roundToInt() - canvasSize / 2
    if (screen == null || screen.width < canvasSize || screen.height < canvasSize) return java.awt.Point(x, y)
    return java.awt.Point(
        x.coerceIn(screen.x, screen.x + screen.width - canvasSize),
        y.coerceIn(screen.y, screen.y + screen.height - canvasSize),
    )
}

private fun screenContaining(tip: CursorPoint): Rectangle? = try {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration.bounds }
        .firstOrNull { it.contains(tip.x, tip.y) }
} catch (e: java.awt.HeadlessException) {
    Log.tag("ComputerUsePointerOverlay").w(e) { "Screen bounds unavailable" }
    null
}

/** Paints one [CursorScene]; [origin] is the canvas' top-left corner in host screen coordinates. */
internal class AgentCursorCanvas(var style: AgentCursorStyle) : JPanel() {
    var scene: CursorScene? = null
    var origin: java.awt.Point = java.awt.Point(0, 0)

    init {
        isOpaque = false
        isFocusable = false
    }

    override fun paintComponent(graphics: Graphics) {
        val current = scene ?: return
        val canvas = graphics.create() as Graphics2D
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val tipX = current.tip.x - origin.x
            val tipY = current.tip.y - origin.y
            paintTrail(canvas, current)
            current.rings.forEach { paintRing(canvas, it, tipX, tipY) }
            paintArrow(canvas, tipX, tipY)
            current.badge?.let { paintBadge(canvas, it, tipX, tipY) }
        } finally {
            canvas.dispose()
        }
    }

    private fun paintTrail(canvas: Graphics2D, scene: CursorScene) {
        canvas.stroke = BasicStroke(style.trailWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        scene.trail.zipWithNext().forEach { (start, end) ->
            canvas.color = style.ripple.withAlpha(end.second)
            canvas.draw(
                java.awt.geom.Line2D.Double(
                    start.first.x - origin.x,
                    start.first.y - origin.y,
                    end.first.x - origin.x,
                    end.first.y - origin.y,
                ),
            )
        }
    }

    private fun paintRing(canvas: Graphics2D, ring: CursorRing, tipX: Double, tipY: Double) {
        val radius = style.rippleRadius * ring.progress.coerceAtLeast(MIN_RING_FRACTION).toDouble()
        canvas.color = style.ripple.withAlpha(ring.alpha)
        canvas.stroke = BasicStroke(style.trailWidth)
        canvas.draw(Ellipse2D.Double(tipX - radius, tipY - radius, radius * 2, radius * 2))
    }

    private fun paintArrow(canvas: Graphics2D, tipX: Double, tipY: Double) {
        val side = style.pointerSize.toDouble() - style.stroke
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
        val local = canvas.create() as Graphics2D
        try {
            local.translate(tipX, tipY)
            local.color = style.fill
            local.fill(arrow)
            local.color = style.outline
            local.stroke = BasicStroke(style.stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            local.draw(arrow)
        } finally {
            local.dispose()
        }
    }

    private fun paintBadge(canvas: Graphics2D, badge: CursorBadge, tipX: Double, tipY: Double) {
        canvas.font = canvas.font.deriveFont(
            if (style.isBadgeBold) Font.BOLD else Font.PLAIN,
            style.badgeTextSize.toFloat(),
        )
        val text = badge.text.takeIf { canvas.font.canDisplayUpTo(it) == -1 } ?: badge.text.plain()
        val metrics = canvas.fontMetrics
        val width = metrics.stringWidth(text) + style.badgePadding * 2.0
        val height = metrics.height + style.badgePadding.toDouble()
        val x = (tipX + style.badgeOffset).coerceAtMost(this.width - width)
        val y = (tipY + style.badgeOffset).coerceAtMost(this.height - height)
        canvas.color = style.badge.withAlpha(badge.alpha)
        canvas.fill(RoundRectangle2D.Double(x, y, width, height, height, height))
        canvas.color = style.badgeContent.withAlpha(badge.alpha)
        val baseline = y + (height - metrics.height) / 2 + metrics.ascent
        canvas.drawString(text, (x + style.badgePadding).toFloat(), baseline.toFloat())
    }

    /** ASCII stand-ins for label symbols a font cannot draw (the default Swing font on some hosts). */
    private fun String.plain(): String = replace("⌨", "Aa").replace("↑", "^").replace("↓", "v")

    private fun Color.withAlpha(fraction: Float): Color =
        Color(red, green, blue, (alpha * fraction.coerceIn(0f, 1f)).roundToInt())

    private companion object {
        const val MIN_RING_FRACTION = 0.2f
    }
}
