package io.aequicor.heartbeat.feature.aisessionenginetransfer.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.TransitionBuilder
import io.aequicor.heartbeat.core.statemachine.TransitionScope
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException

/**
 * Moves the current segment of a logical conversation to another engine by seeding a new native session with a
 * transcript handoff. The source session is only read. No persistence: an interrupted transfer leaves either
 * nothing or a journaled segment whose Pending handoff reads as Unknown.
 *
 * | From | Intent | Guard | To | Effect / output |
 * |---|---|---|---|---|
 * | Idle | Start | target engine differs from source engine | Preparing | Prepare |
 * | Preparing | Prepared | same transfer and requested conversation, source is its current segment | Seeding | Seed |
 * | Preparing | Prepared | same transfer, other conversation / current segment | Idle(Failed) | Unknown / NotLatest |
 * | Preparing | Cancel | same transfer | Idle(Cancelled) | Finished; Prepare is cancelled |
 * | Preparing / Seeding | Failed | same transfer | Idle(Failed) | Finished; from Seeding with the conversation |
 * | Seeding | Seeded | same transfer and conversation | Idle(Completed) | Finished |
 * | Seeding | Seeded | same transfer, other conversation | Idle(Failed(Unknown)) | Finished; with its conversation |
 * | Seeding | Cancel | — | ignored | creation and delivery are not interruptible |
 * | Preparing / Seeding | Start | — | ignored | one transfer at a time |
 *
 * Effect failures map to Failed: EngineException keeps its domain failure; anything else, including a
 * cancellation escaping from inside a running effect, becomes Unknown.
 */
public val SessionTransferMachineSpec:
    MachineSpec<SessionTransferState, SessionTransferIntent, SessionTransferEffect, SessionTransferOutput> =
    machineSpec(SessionTransferMachineKey, SessionTransferState.Idle()) {
        state<SessionTransferState.Idle> {
            on<SessionTransferIntent.Public.Start>(guard = { intent.request.isCrossEngine() }) {
                goto<SessionTransferState.Preparing> { SessionTransferState.Preparing(intent.request) }
                effect { SessionTransferEffect.Prepare(intent.request) }
            }
        }
        state<SessionTransferState.Preparing> {
            on<SessionTransferIntent.Internal.Prepared>(guard = {
                intent.transfer == state.request.transfer && state.request.mismatch(intent.conversation) == null
            }) {
                goto<SessionTransferState.Seeding> {
                    SessionTransferState.Seeding(state.request, intent.conversation.id)
                }
                effect { SessionTransferEffect.Seed(state.request, intent.conversation, intent.prompt) }
            }
            // The effect has finished: ignoring a mismatching result would leave Preparing until a Cancel.
            on<SessionTransferIntent.Internal.Prepared>(guard = {
                intent.transfer == state.request.transfer && state.request.mismatch(intent.conversation) != null
            }) {
                // The guard proved a mismatch; the fallback is unreachable.
                finish {
                    val failure = state.request.mismatch(intent.conversation) ?: TransferFailure.Unknown
                    TransferResult.Failed(intent.transfer, failure)
                }
            }
            on<SessionTransferIntent.Public.Cancel>(guard = { intent.transfer == state.request.transfer }) {
                finish { TransferResult.Cancelled(intent.transfer) }
            }
            on<SessionTransferIntent.Internal.Failed>(guard = { intent.transfer == state.request.transfer }) {
                finish { TransferResult.Failed(intent.transfer, intent.failure) }
            }
        }
        state<SessionTransferState.Seeding> {
            on<SessionTransferIntent.Internal.Seeded>(guard = {
                intent.transfer == state.request.transfer && intent.conversation == state.conversation
            }) {
                finish { TransferResult.Completed(intent.transfer, intent.conversation, intent.segment) }
            }
            // Seeding cannot be cancelled, so a mismatching result must still leave it; the segment was journaled
            // under the reported conversation.
            on<SessionTransferIntent.Internal.Seeded>(guard = {
                intent.transfer == state.request.transfer && intent.conversation != state.conversation
            }) {
                finish { TransferResult.Failed(intent.transfer, TransferFailure.Unknown, intent.conversation) }
            }
            on<SessionTransferIntent.Internal.Failed>(guard = { intent.transfer == state.request.transfer }) {
                finish { TransferResult.Failed(intent.transfer, intent.failure, state.conversation) }
            }
        }
        // The runtime only reports cancellations escaping from inside a still-current effect (e.g. a timeout);
        // they must still finish the transfer, otherwise Seeding would never be left.
        onEffectFailure { effect, error ->
            SessionTransferIntent.Internal.Failed(
                effect.transfer,
                if (error is EngineException) TransferFailure.Engine(error.failure) else TransferFailure.Unknown,
            )
        }
    }

private typealias TransferTransition<T, J> = TransitionBuilder<
    SessionTransferState,
    T,
    SessionTransferIntent,
    J,
    SessionTransferEffect,
    SessionTransferOutput,
>

private fun TransferRequest.isCrossEngine(): Boolean = target.engine != source.engine

/** Why [prepared] cannot be seeded for this request, or null when it matches. */
private fun TransferRequest.mismatch(prepared: LogicalConversation): TransferFailure? = when {
    conversation != null && conversation != prepared.id -> TransferFailure.Unknown
    prepared.current.ref != source -> TransferFailure.NotLatestSegment
    else -> null
}

/** Returns to Idle retaining [result] and emits the single terminal notification. */
private fun <T : SessionTransferState, J : SessionTransferIntent> TransferTransition<T, J>.finish(
    result: TransitionScope<T, J>.() -> TransferResult,
) {
    goto<SessionTransferState.Idle> { SessionTransferState.Idle(result()) }
    output { SessionTransferOutput.Finished(result()) }
}
