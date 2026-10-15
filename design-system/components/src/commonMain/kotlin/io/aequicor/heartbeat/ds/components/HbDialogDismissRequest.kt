package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

/**
 * The nearest modal host's action, or null outside a modal. Embedded native controls use it to forward
 * Escape when their keyboard focus bypasses Compose key dispatch. Call only for the currently attached control;
 * queued native events from a removed or superseded attachment must not dismiss another modal.
 */
public val LocalHbDialogDismissRequest = compositionLocalOf<(() -> Unit)?> { null }

/**
 * The nearest non-modal surface's Escape action, or null outside one. Unlike [LocalHbDialogDismissRequest], this
 * callback does not imply a modal focus cycle or scrim. A nested modal's callback takes precedence over this one.
 * Native hosts must revoke queued requests when the attached control leaves or changes its surface.
 */
public val LocalHbSurfaceDismissRequest = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Forwards native Escape to a non-modal surface's [onDismissRequest] without a dialog, input barrier or focus capture.
 * This scope has no visual treatment; the caller owns layout and Compose key handling.
 */
@Composable
public fun HbDismissRequestProvider(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHbSurfaceDismissRequest provides onDismissRequest, content = content)
}
