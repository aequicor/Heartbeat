package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind

/** User stop addresses the reservation before a native handle exists and retains exact uncertain evidence. */
@Inject
internal class StudioConversationStops(
    private val observations: StudioConversationObservations,
    private val coordinator: StudioRunCoordinator,
) {
    suspend fun cancel(record: StudioChatRecord): Boolean {
        val recovery = coordinator.requestRecoveryStop(record.id)
        if (recovery != null) {
            if (observations.cancelRecovery(record, recovery) is HostConversationCancellation.Unconfirmed) {
                throw EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, recovery))
            }
            return true
        }
        val reserved = coordinator.currentSendRequest(record.id)
        val isJournaled = record.hasAttemptJournal || record.helper != null ||
            record.logicalSession?.kind == SessionKind.Subsession
        if (reserved == null && !isJournaled) return false
        val request = reserved ?: if (record.logicalSession == null) {
            record.lastRunRequest
        } else {
            observations.snapshot(record).attempt?.request
        } ?: return false
        if (observations.cancel(record, request) is HostConversationCancellation.Unconfirmed) {
            throw EngineException(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request))
        }
        return true
    }
}
