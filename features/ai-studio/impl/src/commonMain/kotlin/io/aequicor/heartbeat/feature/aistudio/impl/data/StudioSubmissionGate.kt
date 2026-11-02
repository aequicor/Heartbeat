package io.aequicor.heartbeat.feature.aistudio.impl.data

import kotlinx.coroutines.CancellationException

/**
 * The request owner supplies one gate, retained by the coordinator until the native sender calls [begin].
 * Implementations serialize revocation with begin; only successful begin may invoke native submission.
 */
internal interface StudioSubmissionGate {
    val isCancelled: Boolean
    val hasBegun: Boolean get() = true

    /** A journaled new-send gate cannot observe another request without exact stop routing. */
    val isNativeRetentionSupported: Boolean get() = false
    suspend fun begin()

    /** Retains accepted native work without authorizing a new send; true relays a pending explicit stop. */
    suspend fun retainNative(): Boolean = error("This submission gate cannot retain another native request")
    suspend fun cancel(): Boolean
    suspend fun awaitRevocation(): CancellationException?
}
