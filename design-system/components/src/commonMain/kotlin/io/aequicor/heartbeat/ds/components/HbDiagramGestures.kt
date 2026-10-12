package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import kotlin.math.pow

/** Cancels child clicks only after drag/pinch slop, and leaves non-primary mouse gestures to the host. */
internal fun Modifier.diagramGestures(state: HbDiagramViewportState, onFocus: () -> Unit): Modifier =
    pointerInput(state) { awaitEachGesture { trackDiagramGesture(state, onFocus) } }

private suspend fun AwaitPointerEventScope.trackDiagramGesture(state: HbDiagramViewportState, onFocus: () -> Unit) {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return
    onFocus()
    val motion = DiagramGestureMotion(viewConfiguration.touchSlop)
    do {
        val event = awaitPointerEvent()
        if (event.changes.any { it.isConsumed }) break
        motion.apply(event, state)
    } while (event.changes.any { it.pressed })
}

private class DiagramGestureMotion(private val slop: Float) {
    private var isPastSlop = false
    private var totalPan = Offset.Zero
    private var totalZoom = 1f

    fun apply(event: PointerEvent, state: HbDiagramViewportState) {
        val pan = event.calculatePan()
        val zoom = event.calculateZoom()
        totalPan += pan
        totalZoom *= zoom
        val zoomMotion = abs(1f - totalZoom) * event.calculateCentroidSize(useCurrent = false)
        isPastSlop = isPastSlop || totalPan.getDistance() > slop || zoomMotion > slop
        if (isPastSlop) {
            if (zoom != 1f) state.zoom(state.scale * zoom, event.calculateCentroid(useCurrent = false))
            state.pan(pan)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
    }
}

internal fun Modifier.diagramWheelZoom(state: HbDiagramViewportState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Scroll) event.zoomDiagram(state)
        }
    }
}

private fun PointerEvent.zoomDiagram(state: HbDiagramViewportState) {
    changes.firstOrNull()?.let { change ->
        state.zoom(state.scale * DIAGRAM_ZOOM_STEP.pow(-change.scrollDelta.y), change.position)
        changes.forEach { it.consume() }
    }
}

internal const val DIAGRAM_ZOOM_STEP = 1.2f
