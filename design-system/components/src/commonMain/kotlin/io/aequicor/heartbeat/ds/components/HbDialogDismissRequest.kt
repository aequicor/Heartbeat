package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.compositionLocalOf

/**
 * The nearest modal host's dismissal action, or null outside a modal. Embedded native controls use it to forward
 * Escape when their keyboard focus bypasses Compose key dispatch. Call only for the currently attached control;
 * queued native events from a removed or superseded attachment must not dismiss another modal.
 */
public val LocalHbDialogDismissRequest = compositionLocalOf<(() -> Unit)?> { null }
