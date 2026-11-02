package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperId

/** Stop of observer Q follows its frozen native R, never the chat's latest unrelated turn. */
@Inject
internal class StudioRecoveryCancellation(
    private val attempts: StudioHelperAttempts,
    private val access: Lazy<StudioHelperAccess>,
    private val native: StudioHelperNative,
) {
    /** Null delegates a proven Submit to its existing Q send receipt, or denotes a non-recovery request. */
    suspend fun cancel(record: StudioChatRecord, execution: RequestId): HostConversationCancellation? {
        val helper = HelperId(record.id)
        val targets = attempts.stopRecoveryTargets(helper, execution)
        if (targets.isEmpty()) return null
        val witness = targets.singleOrNull() ?: return HostConversationCancellation.Unconfirmed(execution)
        if (witness.phase == StudioRecoveryPhase.Submit) {
            return if (witness.execution == execution && attempts.receipt(helper, execution) != null) {
                null
            } else if (witness.observed == execution && witness.outcome == StudioHelperTerminalOutcome.Cancelled) {
                terminal(witness, execution)
            } else {
                HostConversationCancellation.Unconfirmed(execution)
            }
        }
        if (witness.phase == StudioRecoveryPhase.Terminal) return terminal(witness, execution)
        if (witness.observed == null || witness.session != record.ref) {
            return HostConversationCancellation.Unconfirmed(execution)
        }
        val stopped = native.stopObserved(record, witness, access.value.liveHelper(helper))
            ?: return HostConversationCancellation.Unconfirmed(execution)
        val observed = attempts.observeRecovery(
            helper,
            witness.execution,
            stopped.session,
            witness.observed,
            stopped.turn,
            stopped.outcome,
        )
        return if (observed.phase == StudioRecoveryPhase.Terminal) {
            HostConversationCancellation.Terminal(stopped.conversationResult(execution).copy(isAnswerComplete = false))
        } else {
            HostConversationCancellation.Unconfirmed(execution)
        }
    }

    /** A terminal-only observation carries no invented answer or native TurnId. */
    private fun terminal(witness: StudioRecoveryWitness, request: RequestId): HostConversationCancellation =
        HostConversationCancellation.Terminal(
            HostConversationResult(
                request,
                when (checkNotNull(witness.outcome)) {
                    StudioHelperTerminalOutcome.Completed -> TurnOutcome.Completed
                    StudioHelperTerminalOutcome.Cancelled -> TurnOutcome.Cancelled
                    StudioHelperTerminalOutcome.Failed -> TurnOutcome.Failed(EngineFailure.Unknown())
                    StudioHelperTerminalOutcome.Unknown -> error("Unknown observation is not terminal")
                },
                "",
                isAnswerComplete = false,
            ),
        )
}
