package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbScrollbarState
import kotlin.math.min

/** One transform for drawing, hit testing, keyboard navigation and scrollbars; all geometry is in viewport pixels. */
@Stable
internal class HbDiagramViewportState(val image: Size) {
    var viewport: Size by mutableStateOf(Size.Zero)
        private set
    var scale: Float by mutableFloatStateOf(1f)
        private set
    var offset: Offset by mutableStateOf(Offset.Zero)
        private set
    private var isFitted = true
    val fitScale: Float get() = if (viewport.width > 0f && viewport.height > 0f) {
        min(viewport.width / image.width, viewport.height / image.height).coerceAtMost(1f)
    } else {
        1f
    }
    val minimumScale: Float get() = min(0.1f, fitScale)
    val scaledSize: Size get() = Size(image.width * scale, image.height * scale)
    val horizontal: HbScrollbarState = Axis(isVertical = false)
    val vertical: HbScrollbarState = Axis(isVertical = true)

    @HighFrequency
    fun resize(size: Size) {
        log.v { "Diagram viewport transform updated" }
        if (size == viewport || size.width <= 0f || size.height <= 0f) return
        val previousCenter = Offset(viewport.width / 2f, viewport.height / 2f)
        viewport = size
        if (isFitted) {
            fit()
        } else {
            offset += Offset(size.width / 2f, size.height / 2f) - previousCenter
            clamp()
        }
    }

    @HighFrequency
    fun fit() {
        log.v { "Diagram viewport transform updated" }
        scale = fitScale
        offset = Offset((viewport.width - scaledSize.width) / 2f, (viewport.height - scaledSize.height) / 2f)
        isFitted = true
    }

    @HighFrequency
    fun zoom(value: Float, anchor: Offset = Offset(viewport.width / 2f, viewport.height / 2f)) {
        log.v { "Diagram viewport zoom changed" }
        if (!value.isFinite() || value <= 0f) return
        val next = value.coerceIn(minimumScale, MAX_DIAGRAM_SCALE)
        offset = anchor - (anchor - offset) * (next / scale)
        scale = next
        isFitted = false
        clamp()
    }

    @HighFrequency
    fun pan(delta: Offset) {
        log.v { "Diagram viewport transform updated" }
        offset += delta
        isFitted = false
        clamp()
    }

    @HighFrequency
    private fun clamp() {
        log.v { "Diagram viewport transform updated" }
        offset = Offset(
            clampAxis(offset.x, viewport.width, scaledSize.width),
            clampAxis(offset.y, viewport.height, scaledSize.height),
        )
    }

    private fun clampAxis(value: Float, available: Float, content: Float): Float =
        if (content <= available) (available - content) / 2f else value.coerceIn(available - content, 0f)

    private inner class Axis(val isVertical: Boolean) : HbScrollbarState {
        private val available: Float get() = if (isVertical) viewport.height else viewport.width
        private val content: Float get() = if (isVertical) scaledSize.height else scaledSize.width
        private val start: Float get() = if (isVertical) offset.y else offset.x
        override val position: Float get() = if (content > available) (0f - start) / (content - available) else 0f
        override val extent: Float get() = if (content > 0f) (available / content).coerceIn(0f, 1f) else 1f
        override val isScrollInProgress: Boolean get() = false
        override fun scrollTo(position: Float) {
            val next = -(content - available).coerceAtLeast(0f) * position.coerceIn(0f, 1f)
            pan(if (isVertical) Offset(0f, next - start) else Offset(next - start, 0f))
        }
    }
}

internal const val MAX_DIAGRAM_SCALE = 8f

private val log = Log.tag("DS/DiagramViewport")
