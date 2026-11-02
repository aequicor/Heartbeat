package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.CancellationException

/** The caller holds StudioHelperAttempts' common mutex; storage helpers never acquire a separate journal lock. */
internal suspend fun KeyValueStore.requireNativeAdmission(
    helper: HelperId,
    receipts: Map<String, StudioHelperReceipt>,
    request: RequestId,
    incoming: StudioAttemptPolicy?,
) {
    val chain = continuationChain(receipts, request)
    if (chain.size > 1 && chain.any { it.isContinuationFenced }) {
        throw CancellationException("Execution lineage was stopped")
    }
    val recoveries = readRecoveryWitnesses(helper)
    val policy = receipts[request.value]?.policy ?: incoming
    val root = recoveries[request.value]?.root ?: (policy as? StudioAttemptPolicy.GraphWork)?.recoveryRoot
    if (root != null && readLegacyObservations(helper)[root.value]?.isStopRequested == true) {
        throw CancellationException("Legacy work root was explicitly stopped")
    }
    requireRecoveryAdmission(recoveries.values, receipts, request)
}

/** Tracked conversations cannot bypass an absent last-send receipt through a fresh independent admission. */
internal fun StudioChatRecord?.hasRecordedAttemptHistory(records: Map<String, StudioHelperReceipt>): Boolean {
    val isTracked = this?.hasAttemptJournal == true || this?.helper != null ||
        this?.identity?.context?.kind in setOf(SessionKind.Subsession, SessionKind.Subagent)
    val known = this?.lastRunRequest
    return !isTracked || known == null || known.value in records
}
