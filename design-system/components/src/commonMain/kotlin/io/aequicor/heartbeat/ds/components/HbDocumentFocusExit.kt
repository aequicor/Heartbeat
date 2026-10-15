package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.compositionLocalOf

/**
 * Requests the current document overlay's keyboard target when native content traverses past its first or last
 * control. Native hosts must revoke queued requests when their attachment changes or leaves the composition.
 */
public val LocalHbDocumentFocusExit = compositionLocalOf<(() -> Unit)?> { null }
