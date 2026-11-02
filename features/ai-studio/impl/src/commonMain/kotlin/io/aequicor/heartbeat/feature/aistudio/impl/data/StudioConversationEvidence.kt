package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationAttempt
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationAttemptPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperId

internal interface StudioConversationObservations {
    suspend fun snapshot(record: StudioChatRecord): HostConversationSnapshot
    suspend fun result(record: StudioChatRecord, request: RequestId): HostConversationResult?
    suspend fun cancel(record: StudioChatRecord, request: RequestId): HostConversationCancellation

    /** Captured recovery Q may lack a witness after failed initialization; this never selects a later turn. */
    suspend fun cancelRecovery(record: StudioChatRecord, request: RequestId): HostConversationCancellation =
        HostConversationCancellation.Unconfirmed(request)
}

/** Evidence comes from the exact receipt/native request; neither a missing job nor an answer implies completion. */
@ContributesBinding(ProfileScope::class, binding = binding<StudioConversationObservations>())
@Inject
internal class StudioConversationEvidence(
    private val attempts: StudioHelperAttempts,
    private val runs: Lazy<StudioHelperRuns>,
    private val access: Lazy<StudioHelperAccess>,
    private val dispatch: StudioConversationDispatch,
    private val recoveries: StudioRecoveryCancellation? = null,
) : StudioConversationObservations {
    override suspend fun snapshot(record: StudioChatRecord): HostConversationSnapshot {
        val helper = HelperId(record.id)
        val request = attempts.unresolved(
            helper,
        ) ?: dispatch.pendingRequest(LogicalSessionId(record.id)) ?: record.lastRunRequest
        val receipt = request?.let { attempts.receipt(helper, it) }
        val live = receipt?.session?.let { native ->
            access.value.liveHelper(helper)?.takeIf { it.ref == native }?.state?.value
        }
        val attempt = receipt?.let {
            HostConversationAttempt(it.request, phase(record, it, live), it.session ?: it.preparedSession, it.turn)
        } ?: request?.let {
            val phase = if (dispatch.isPending(LogicalSessionId(record.id), it)) {
                HostConversationAttemptPhase.Queued
            } else {
                HostConversationAttemptPhase.Unknown
            }
            HostConversationAttempt(it, phase)
        }
        return HostConversationSnapshot(
            checkNotNull(record.logicalSession),
            record.title,
            dispatch.initialTarget(record),
            attempt,
        )
    }

    override suspend fun result(record: StudioChatRecord, request: RequestId): HostConversationResult? {
        val helper = HelperId(record.id)
        val receipt = attempts.continuation(
            helper,
            request,
        )?.result ?: return attempts.legacy(helper, request)?.terminalResult()
        val terminal = receipt.terminal ?: if (receipt.phase in NATIVE_PHASES) {
            runs.value.helperTerminal(helper, receipt.request)?.also { attempts.terminal(helper, receipt.request, it) }
        } else {
            null
        }
        return terminal?.conversationResult(request)
    }

    override suspend fun cancel(record: StudioChatRecord, request: RequestId): HostConversationCancellation {
        val helper = HelperId(record.id)
        val observed = recoveries?.cancel(record, request) ?: legacyCancellation(helper, request)
        if (observed != null) return observed
        val receipt = attempts.requestStop(helper, request)
        return when {
            receipt.phase == StudioHelperPhase.NotSubmitted -> if (receipt.request == request) {
                dispatch.retirePending(LogicalSessionId(record.id), request)
                HostConversationCancellation.NotSubmitted(request)
            } else {
                attempts.continuation(helper, request)?.result?.terminal?.let {
                    HostConversationCancellation.Terminal(it.conversationResult(request))
                } ?: HostConversationCancellation.Unconfirmed(request)
            }

            receipt.terminal != null -> HostConversationCancellation.Terminal(
                receipt.terminal.conversationResult(request),
            )

            else -> {
                val terminal = runs.value.stopHelper(helper, receipt.request)
                if (terminal == null) {
                    HostConversationCancellation.Unconfirmed(request)
                } else {
                    attempts.terminal(helper, receipt.request, terminal)
                    HostConversationCancellation.Terminal(terminal.conversationResult(request))
                }
            }
        }
    }

    private suspend fun legacyCancellation(helper: HelperId, request: RequestId): HostConversationCancellation? {
        val legacy = attempts.stopLegacy(helper, request) ?: return null
        val result = legacy.terminalResult()
        return if (result == null) {
            HostConversationCancellation.Unconfirmed(request)
        } else {
            HostConversationCancellation.Terminal(result)
        }
    }

    override suspend fun cancelRecovery(record: StudioChatRecord, request: RequestId): HostConversationCancellation {
        if (attempts.recovery(HelperId(record.id), request) == null) {
            return HostConversationCancellation.Unconfirmed(request)
        }
        return cancel(record, request)
    }

    private fun phase(
        record: StudioChatRecord,
        receipt: StudioHelperReceipt,
        live: ActiveSessionState?,
    ): HostConversationAttemptPhase = when (receipt.phase) {
        StudioHelperPhase.Terminal -> HostConversationAttemptPhase.Finished

        StudioHelperPhase.NotSubmitted -> HostConversationAttemptPhase.NotSubmitted

        StudioHelperPhase.Preparing -> if (dispatch.isPending(LogicalSessionId(record.id), receipt.request)) {
            HostConversationAttemptPhase.Queued
        } else {
            HostConversationAttemptPhase.Unknown
        }

        StudioHelperPhase.Submitting -> when {
            receipt.isStopRequested -> HostConversationAttemptPhase.StopUnconfirmed
            dispatch.isPending(LogicalSessionId(record.id), receipt.request) -> HostConversationAttemptPhase.Submitting
            else -> HostConversationAttemptPhase.Unknown
        }

        StudioHelperPhase.Accepted -> activePhase(receipt, live)
    }

    private fun activePhase(receipt: StudioHelperReceipt, live: ActiveSessionState?): HostConversationAttemptPhase {
        if (live?.activeTurn()?.request != receipt.request) {
            return uncertainPhase(receipt)
        }
        return when (live) {
            is ActiveSessionState.Interrupting -> HostConversationAttemptPhase.Stopping

            is ActiveSessionState.Running -> if (receipt.isStopRequested) {
                HostConversationAttemptPhase.StopUnconfirmed
            } else {
                HostConversationAttemptPhase.Running
            }

            is ActiveSessionState.AwaitingUserAction -> if (receipt.isStopRequested) {
                HostConversationAttemptPhase.StopUnconfirmed
            } else {
                HostConversationAttemptPhase.Waiting
            }

            is ActiveSessionState.Ready, is ActiveSessionState.Submitting, is ActiveSessionState.Unavailable,
            is ActiveSessionState.Closing, ActiveSessionState.Closed,
            -> uncertainPhase(receipt)
        }
    }

    private fun uncertainPhase(receipt: StudioHelperReceipt): HostConversationAttemptPhase =
        if (receipt.isStopRequested) {
            HostConversationAttemptPhase.StopUnconfirmed
        } else {
            HostConversationAttemptPhase.Unknown
        }

    private companion object {
        val NATIVE_PHASES = setOf(StudioHelperPhase.Submitting, StudioHelperPhase.Accepted)
    }
}

internal fun StudioHelperTerminal.conversationResult(request: RequestId): HostConversationResult =
    HostConversationResult(
        request,
        when (outcome) {
            StudioHelperTerminalOutcome.Completed -> TurnOutcome.Completed
            StudioHelperTerminalOutcome.Failed -> TurnOutcome.Failed(EngineFailure.Unknown())
            StudioHelperTerminalOutcome.Cancelled -> TurnOutcome.Cancelled
            StudioHelperTerminalOutcome.Unknown -> TurnOutcome.Unknown
        },
        answer,
        isAnswerComplete = isAnswerComplete,
    )
