package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/** The host's initial pointer modality; real pointer events can override it on hybrid devices. */
internal expect fun defaultNavigationUsesTouch(): Boolean

/** Observes the physical pointer without consuming row, nested action, or scrolling gestures. */
internal fun Modifier.navigationPointerInput(onTouchChanged: (Boolean) -> Unit): Modifier =
    pointerInput(onTouchChanged) {
        awaitPointerEventScope {
            while (true) {
                val pointer = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()
                when (pointer?.type) {
                    PointerType.Mouse -> onTouchChanged(false)
                    PointerType.Touch, PointerType.Stylus, PointerType.Eraser -> onTouchChanged(true)
                    else -> Unit
                }
            }
        }
    }
