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
        if (hasRevokedSend(helper, witness, execution)) return null
        val closed = closed(witness, execution)
        if (closed != null) return closed
        if (witness.observed == null || witness.session != record.ref) {
            return HostConversationCancellation.Unconfirmed(execution)
        }
        return stopObserved(record, witness, execution)
    }

    private suspend fun stopObserved(
        record: StudioChatRecord,
        witness: StudioRecoveryWitness,
        execution: RequestId,
    ): HostConversationCancellation {
        val helper = HelperId(record.id)
        if (witness.route != null) witness.requireRoute(record)
        val stopped = native.stopObserved(record, witness, access.value.liveHelper(helper))
            ?: return HostConversationCancellation.Unconfirmed(execution)
        val observed = recordStopped(helper, witness, stopped)
        return if (observed.phase == StudioRecoveryPhase.Terminal) {
            HostConversationCancellation.Terminal(stopped.conversationResult(execution).copy(isAnswerComplete = false))
        } else {
            HostConversationCancellation.Unconfirmed(execution)
        }
    }

    /** A native stop may finish after restart; its old delivery must not overwrite the new observer. */
    private suspend fun recordStopped(
        helper: HelperId,
        witness: StudioRecoveryWitness,
        stopped: StudioHelperTerminal,
    ): StudioRecoveryWitness {
        val request = checkNotNull(witness.observed)
        val delivery = witness.delivery
        return if (delivery == null) {
            attempts.observeRecovery(helper, witness.execution, stopped.session, request, stopped.turn, stopped.outcome)
        } else {
            attempts.changeRecoveryDelivery(delivery) {
                it.observed(request, stopped.session, stopped.turn, stopped.outcome)
            }
        }
    }

    private suspend fun hasRevokedSend(helper: HelperId, witness: StudioRecoveryWitness, request: RequestId): Boolean {
        if (witness.phase != StudioRecoveryPhase.NoSend || witness.execution != request ||
            witness.observed == request
        ) {
            return false
        }
        return attempts.receipt(helper, request)?.phase == StudioHelperPhase.NotSubmitted
    }

    private fun closed(witness: StudioRecoveryWitness, request: RequestId): HostConversationCancellation? =
        when (witness.phase) {
            StudioRecoveryPhase.Terminal -> terminal(witness, request)

            StudioRecoveryPhase.NoSend -> if (witness.outcome == StudioHelperTerminalOutcome.Cancelled) {
                terminal(witness, request)
            } else {
                HostConversationCancellation.Unconfirmed(request)
            }

            StudioRecoveryPhase.Inspecting, StudioRecoveryPhase.Submit,
            StudioRecoveryPhase.Observing, StudioRecoveryPhase.Unknown,
            -> null
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
