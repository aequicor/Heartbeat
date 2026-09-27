package io.aequicor.heartbeat.ds.layouts

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.unit.IntSize
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val scrollbarLog = Log.tag("DS/Scrollbar")

internal class HbScrollbarInteraction(val adapter: HbScrollbarAdapter, private val scope: CoroutineScope) {
    var isHovered: Boolean by mutableStateOf(false)
    var isDragging: Boolean by mutableStateOf(false)
    var isVisible: Boolean by mutableStateOf(false)
    var isThumbControlled: Boolean by mutableStateOf(false)
    var draggedPosition: Float by mutableFloatStateOf(0f)
    private var seekJob: Job? = null

    fun seek(position: Float) {
        if (!isThumbControlled) scrollbarLog.d { "scrollbar seeking started" }
        draggedPosition = position.coerceIn(0f, 1f)
        isThumbControlled = true
        seekJob?.cancel()
        seekJob = scope.launch { adapter.seek(draggedPosition) }
    }

    fun finishDrag() {
        if (isDragging) scrollbarLog.d { "scrollbar seeking released" }
        isDragging = false
        val pending = seekJob
        scope.launch {
            pending?.join()
            if (!isDragging && pending === seekJob) isThumbControlled = false
        }
    }
}

internal data class HbScrollbarGeometry(
    val thickness: Float,
    val hoverThickness: Float,
    val hoverWidth: Float,
    val minimumThumb: Float,
    val inset: Float,
)

internal class HbScrollbarPointer(
    private val interaction: HbScrollbarInteraction,
    private val geometry: HbScrollbarGeometry,
    private val orientation: Orientation,
    private val isReversed: Boolean,
    private val isRtl: Boolean,
) {
    private var activePointer: PointerId? = null
    private var grabOffset = 0f

    fun onEvent(event: PointerEvent, size: IntSize) {
        val change = event.changes.firstOrNull { it.id == activePointer } ?: event.changes.firstOrNull() ?: return
        val metrics = interaction.adapter.metrics()
        val isInside = metrics.isScrollable && hitRegion(change.position, size)
        interaction.isHovered = change.type == PointerType.Mouse && isInside && event.type != PointerEventType.Exit
        if (activePointer == null) {
            if (event.buttons.isPrimaryPressed) startDrag(change, size, metrics, isInside)
        } else {
            continueDrag(change, size, metrics)
        }
    }

    private fun startDrag(change: PointerInputChange, size: IntSize, metrics: HbScrollbarMetrics, isInside: Boolean) {
        // The narrow desktop handle is only an indicator for touch: fingers retain normal swipes.
        if (change.type != PointerType.Mouse || change.isConsumed || !isInside) return
        if (!change.changedToDownIgnoreConsumed()) return
        val thumb = thumb(metrics, size)
        if (thumb.travel <= 0f) return
        activePointer = change.id
        interaction.isDragging = true
        val position = axis(change.position)
        val isOnThumb = position >= thumb.start && position <= thumb.start + thumb.length
        grabOffset = if (isOnThumb) position - thumb.start else thumb.length / 2f
        change.consume()
        scrollbarLog.d { "scrollbar gesture started orientation=$orientation trackSeek=${!isOnThumb}" }
        if (!isOnThumb) seek(position, thumb)
    }

    private fun continueDrag(change: PointerInputChange, size: IntSize, metrics: HbScrollbarMetrics) {
        if (change.id != activePointer) return
        change.consume()
        if (change.changedToUpIgnoreConsumed() || !change.pressed) {
            activePointer = null
            interaction.finishDrag()
            scrollbarLog.d { "scrollbar gesture finished orientation=$orientation" }
        } else {
            seek(axis(change.position), thumb(metrics, size))
        }
    }

    private fun seek(position: Float, thumb: HbScrollbarThumb) {
        if (thumb.travel <= 0f) return
        val physical = ((position - thumb.trackStart - grabOffset) / thumb.travel).coerceIn(0f, 1f)
        interaction.seek(if (isReversed) 1f - physical else physical)
    }

    private fun thumb(metrics: HbScrollbarMetrics, size: IntSize): HbScrollbarThumb = scrollbarThumb(
        metrics,
        if (orientation == Orientation.Vertical) size.height.toFloat() else size.width.toFloat(),
        geometry.inset,
        geometry.minimumThumb,
        isReversed,
        if (interaction.isThumbControlled) interaction.draggedPosition else null,
    )

    private fun axis(position: Offset): Float = if (orientation == Orientation.Vertical) position.y else position.x

    private fun hitRegion(position: Offset, size: IntSize): Boolean {
        if (position.x !in 0f..size.width.toFloat() || position.y !in 0f..size.height.toFloat()) return false
        return when {
            orientation == Orientation.Horizontal -> position.y >= size.height - geometry.hoverWidth
            isRtl -> position.x <= geometry.hoverWidth
            else -> position.x >= size.width - geometry.hoverWidth
        }
    }
}

internal suspend fun AwaitPointerEventScope.receiveScrollbarEvents(pointer: HbScrollbarPointer) {
    while (true) pointer.onEvent(awaitPointerEvent(PointerEventPass.Initial), size)
}
