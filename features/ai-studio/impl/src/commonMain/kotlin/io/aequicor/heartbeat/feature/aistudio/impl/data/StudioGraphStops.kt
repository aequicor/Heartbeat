package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperId

/** Historical migration evidence proves quiescence without fabricating a terminal outcome or a send receipt. */
internal suspend fun stopGraphAssignments(
    known: Map<String, GraphChatAttempt>,
    record: StudioChatRecord,
    current: RequestId?,
    attempts: StudioHelperAttempts,
    cancel: suspend (RequestId) -> HostConversationCancellation,
): Boolean {
    val roots = known.keys.map { known.graphWorkRoot(record.id, it) }.toSet()
    if (roots.isEmpty()) return false
    val helper = HelperId(record.id)
    var isConfirmed = true
    for (root in roots) {
        val request = RequestId(root)
        val work = attempts.recoveryWork(helper, request)
        val isLocalPreparationRevocable = current == request && root in known
        val isStopped = if (work.receipt == null && work.observer == null && !isLocalPreparationRevocable) {
            // This is the same exact Idle/Terminal proof that permitted the first independent universal writer.
            // Persist Stop even for Idle: it revokes old work, but does not invent that work's outcome.
            attempts.stopLegacy(helper, request)?.phase in setOf(StudioLegacyPhase.Idle, StudioLegacyPhase.Terminal)
        } else {
            when (val result = cancel(request)) {
                is HostConversationCancellation.NotSubmitted -> true
                is HostConversationCancellation.Terminal -> result.result.outcome != TurnOutcome.Unknown
                is HostConversationCancellation.Unconfirmed -> false
            }
        }
        isConfirmed = isStopped && isConfirmed
    }
    return isConfirmed
}
