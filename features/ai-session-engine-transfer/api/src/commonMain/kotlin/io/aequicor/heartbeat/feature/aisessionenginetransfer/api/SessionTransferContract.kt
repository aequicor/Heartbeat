package io.aequicor.heartbeat.feature.aisessionenginetransfer.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest

/** One transfer at a time per profile. Durable progress lives in the conversation journal, not in this state. */
public sealed interface SessionTransferState : MachineState {
    /** Ready for a transfer; the last terminal result is retained for late subscribers. */
    public data class Idle(val last: TransferResult? = null) : SessionTransferState

    /** Checking toggles and the conversation, reading source history and composing the handoff. Cancellable. */
    public data class Preparing(val request: TransferRequest) : SessionTransferState

    /**
     * Creating the target session and submitting the handoff. Not cancellable: interrupting would leave an
     * unrecorded session or an ambiguous delivery.
     */
    public data class Seeding(val request: TransferRequest, val conversation: ConversationId) : SessionTransferState
}

/** Commands from other features and results of transfer effects. Stale transfer ids are ignored. */
public sealed interface SessionTransferIntent : MachineIntent {
    /** Commands sent through [SessionTransferMachineKey]. */
    public sealed interface Public : SessionTransferIntent {
        /** Starts a transfer; ignored while another one runs or when the target engine equals the source engine. */
        public data class Start(val request: TransferRequest) : Public

        /** Cancels a transfer that has not started creating the target session yet. */
        public data class Cancel(val transfer: TransferId) : Public
    }

    /** Results of effects. */
    public sealed interface Internal : SessionTransferIntent {
        /** The handoff prompt is composed for [conversation], whose current segment is the source. */
        public data class Prepared(
            val transfer: TransferId,
            val conversation: LogicalConversation,
            val prompt: PromptRequest,
        ) : Internal

        /** The target session is recorded as the new current segment with a settled handoff status. */
        public data class Seeded(
            val transfer: TransferId,
            val conversation: ConversationId,
            val segment: ConversationSegment,
        ) : Internal {
            init {
                require(segment.handoff != null && segment.handoff.status != HandoffStatus.Pending) {
                    "A seeded segment has a settled handoff"
                }
            }
        }

        /** The transfer stopped without appending a segment. */
        public data class Failed(val transfer: TransferId, val failure: TransferFailure) : Internal
    }
}

/** IO commands executed in the feature impl. */
public sealed interface SessionTransferEffect : MachineEffect {
    /** Transfer this effect belongs to. */
    public val transfer: TransferId

    /** Validates toggles and the conversation, reads source history and composes the handoff prompt. */
    public data class Prepare(val request: TransferRequest) : SessionTransferEffect {
        override val transfer: TransferId get() = request.transfer
    }

    /** Creates the target session, journals it before submission and submits the handoff exactly once. */
    public data class Seed(
        val request: TransferRequest,
        val conversation: LogicalConversation,
        val prompt: PromptRequest,
    ) : SessionTransferEffect {
        override val transfer: TransferId get() = request.transfer
    }
}

/** Transient notifications; [SessionTransferState.Idle.last] stays authoritative for late subscribers. */
public sealed interface SessionTransferOutput : MachineOutput {
    /** Exactly one terminal notification per started transfer. */
    public data class Finished(val result: TransferResult) : SessionTransferOutput
}

/**
 * Profile-scoped transfer machine. It is created lazily on first injection in the profile, so
 * `MachineRegistry.send` returns `NotRunning` until a consumer in the profile has instantiated it.
 */
public object SessionTransferMachineKey :
    MachineKey<
        SessionTransferState,
        SessionTransferIntent,
        SessionTransferIntent.Public,
        SessionTransferEffect,
        SessionTransferOutput,
    > {
    override val name: String = "ai-session-transfer"
}
