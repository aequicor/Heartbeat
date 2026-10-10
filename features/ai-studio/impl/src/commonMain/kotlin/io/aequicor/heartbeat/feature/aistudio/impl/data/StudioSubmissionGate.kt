package io.aequicor.heartbeat.feature.aistudio.impl.data

import kotlinx.coroutines.CancellationException

/**
 * The request owner supplies one gate, retained by the coordinator until the native sender calls [begin].
 * Implementations serialize revocation with begin; only successful begin may invoke native submission.
 */
internal interface StudioSubmissionGate {
    val isCancelled: Boolean
    suspend fun begin()
    suspend fun cancel(): Boolean
    suspend fun awaitRevocation(): CancellationException?
}
