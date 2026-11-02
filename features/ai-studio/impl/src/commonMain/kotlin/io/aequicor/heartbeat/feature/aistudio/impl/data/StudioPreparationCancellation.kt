package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

/** A predecessor keeps its native evidence forever, even after the successor becomes terminal. */
internal fun Map<String, StudioHelperReceipt>.isSupersededAttempt(request: RequestId): Boolean =
    continuationChain(this, request).lastOrNull()?.let { it.request != request } == true

/** Pure preparation cancellation; the owner persists the returned map under its existing journal mutex. */
internal fun cancelPreparationReceipt(
    records: Map<String, StudioHelperReceipt>,
    request: RequestId,
    isFinal: Boolean,
): Pair<Map<String, StudioHelperReceipt>, StudioHelperReceipt> {
    val receipt = records[request.value]
    if (records.isSupersededAttempt(request)) {
        val changed = if (isFinal) stopContinuationChain(records, request, shouldStopNative = true).first else records
        return changed to changed.getValue(request.value)
    }
    val isFinalized = receipt?.phase == StudioHelperPhase.NotSubmitted && isFinal && !receipt.isFinal
    val isBeforeNative = receipt?.phase == StudioHelperPhase.Preparing ||
        (
            receipt?.phase == StudioHelperPhase.Submitting &&
                receipt.isNativeEntryTracked && receipt.nativeEntryClaim == null
        )
    if (receipt != null && !isBeforeNative && !isFinalized) return records to receipt
    val cancelled = receipt?.copy(phase = StudioHelperPhase.NotSubmitted, isFinal = isFinal || receipt.isFinal)
        ?: StudioHelperReceipt(request, StudioHelperPhase.NotSubmitted, null, isFinal = isFinal)
    return (records + (request.value to cancelled)) to cancelled
}

/** Only the exact local sender may prove that its native call was not entered or was definitively refused. */
internal fun StudioHelperReceipt.ownsUnsentBoundary(session: SessionRef?, claim: String): Boolean {
    val isBound = (preparedSession == null || preparedSession == session) && this.session == null
    val isOwner = phase == StudioHelperPhase.Preparing ||
        (phase == StudioHelperPhase.Submitting && submissionClaim == claim)
    return isBound && isOwner
}
