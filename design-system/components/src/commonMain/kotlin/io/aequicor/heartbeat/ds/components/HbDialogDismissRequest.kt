package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

/**
 * The nearest dismissible surface's action, or null outside one. Embedded native controls use it to forward
 * Escape when their keyboard focus bypasses Compose key dispatch. Call only for the currently attached control;
 * queued native events from a removed or superseded attachment must not dismiss another surface.
 */
public val LocalHbDialogDismissRequest = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Forwards native Escape to a non-modal surface's [onDismissRequest] without a dialog, input barrier or focus capture.
 * This scope has no visual treatment; the caller owns layout and Compose key handling.
 */
@Composable
public fun HbDismissRequestProvider(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHbDialogDismissRequest provides onDismissRequest, content = content)
}
