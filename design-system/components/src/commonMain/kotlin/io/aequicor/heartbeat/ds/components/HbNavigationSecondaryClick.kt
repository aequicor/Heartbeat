package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import io.aequicor.heartbeat.core.logging.Log

/** Secondary activation opens the same caller-owned menu without triggering the primary row action. */
@Composable
internal fun Modifier.navigationSecondaryClick(onSecondaryClick: (() -> Unit)?): Modifier {
    val currentAction by rememberUpdatedState(onSecondaryClick)
    if (onSecondaryClick == null) return this
    return pointerInput(Unit) {
        awaitPointerEventScope {
            var isArmed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Press -> isArmed = event.buttons.isSecondaryPressed

                    PointerEventType.Exit -> isArmed = false

                    PointerEventType.Release -> {
                        if (isArmed) {
                            Log.tag("DS/Navigation").i { "navigation context menu requested" }
                            currentAction?.invoke()
                            event.changes.forEach { it.consume() }
                        }
                        isArmed = false
                    }
                }
            }
        }
    }
}
