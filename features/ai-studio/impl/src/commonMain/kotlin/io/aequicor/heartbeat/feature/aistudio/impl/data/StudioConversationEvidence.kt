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
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits

internal interface StudioConversationObservations {
    suspend fun snapshot(record: StudioChatRecord): HostConversationSnapshot
    suspend fun result(record: StudioChatRecord, request: RequestId): HostConversationResult?
    suspend fun cancel(record: StudioChatRecord, request: RequestId): HostConversationCancellation
}

/** Evidence comes from the exact receipt/native request; neither a missing job nor an answer implies completion. */
@ContributesBinding(ProfileScope::class, binding = binding<StudioConversationObservations>())
@Inject
internal class StudioConversationEvidence(
    private val attempts: StudioHelperAttempts,
    private val runs: Lazy<StudioHelperRuns>,
    private val access: Lazy<StudioHelperAccess>,
    private val dispatch: StudioConversationDispatch,
) : StudioConversationObservations {
    override suspend fun snapshot(record: StudioChatRecord): HostConversationSnapshot {
        val helper = HelperId(record.id)
        val request = attempts.unresolved(helper) ?: record.lastRunRequest
        val receipt = request?.let { attempts.receipt(helper, it) }
        val live = access.value.liveHelper(helper)?.takeIf { it.ref == receipt?.session }?.state?.value
        val attempt = receipt?.let {
            HostConversationAttempt(it.request, phase(record, it, live), it.session ?: it.preparedSession, it.turn)
        } ?: request?.let { HostConversationAttempt(it, HostConversationAttemptPhase.Unknown) }
        return HostConversationSnapshot(
            checkNotNull(record.logicalSession),
            record.title,
            dispatch.initialTarget(record),
            attempt,
        )
    }

    override suspend fun result(record: StudioChatRecord, request: RequestId): HostConversationResult? {
        val helper = HelperId(record.id)
        val receipt = attempts.receipt(helper, request) ?: return null
        val terminal = receipt.terminal ?: if (receipt.phase in NATIVE_PHASES) {
            runs.value.helperTerminal(helper, request)?.also { attempts.terminal(helper, request, it) }
        } else {
            null
        }
        return terminal?.conversationResult(request)
    }

    override suspend fun cancel(record: StudioChatRecord, request: RequestId): HostConversationCancellation {
        val helper = HelperId(record.id)
        val receipt = attempts.requestStop(helper, request)
        return when {
            receipt.phase == StudioHelperPhase.NotSubmitted -> HostConversationCancellation.NotSubmitted(request)

            receipt.terminal != null -> HostConversationCancellation.Terminal(
                receipt.terminal.conversationResult(request),
            )

            else -> {
                val terminal = runs.value.stopHelper(helper, request)
                if (terminal == null) {
                    HostConversationCancellation.Unconfirmed(request)
                } else {
                    attempts.terminal(helper, request, terminal)
                    HostConversationCancellation.Terminal(terminal.conversationResult(request))
                }
            }
        }
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

private fun StudioHelperTerminal.conversationResult(request: RequestId): HostConversationResult =
    HostConversationResult(
        request,
        when (outcome) {
            StudioHelperTerminalOutcome.Completed -> TurnOutcome.Completed
            StudioHelperTerminalOutcome.Failed -> TurnOutcome.Failed(EngineFailure.Unknown())
            StudioHelperTerminalOutcome.Cancelled -> TurnOutcome.Cancelled
            StudioHelperTerminalOutcome.Unknown -> TurnOutcome.Unknown
        },
        answer,
        isAnswerComplete = answer.length < SchedulerLimits.MAX_PAYLOAD &&
            outcome != StudioHelperTerminalOutcome.Unknown,
    )
