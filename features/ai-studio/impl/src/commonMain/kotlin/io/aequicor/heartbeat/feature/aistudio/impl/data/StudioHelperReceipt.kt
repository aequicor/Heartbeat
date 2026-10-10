package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import kotlinx.serialization.Serializable

@Serializable
internal enum class StudioHelperPhase { Preparing, Submitting, Accepted, Terminal, NotSubmitted }

@Serializable
internal enum class StudioHelperTerminalOutcome { Completed, Failed, Cancelled, Unknown }

/** Exact native identity with a bounded final answer. Diagnostic output never includes that answer. */
@Serializable
internal data class StudioHelperTerminal(
    val session: SessionRef,
    val turn: TurnId,
    val outcome: StudioHelperTerminalOutcome,
    val answer: String,
) {
    init {
        require(answer.length <= SchedulerLimits.MAX_PAYLOAD)
    }

    fun result(request: RequestId): HelperResult = HelperResult(
        request,
        when (outcome) {
            StudioHelperTerminalOutcome.Completed -> HelperOutcome.Completed
            StudioHelperTerminalOutcome.Failed -> HelperOutcome.Failed
            StudioHelperTerminalOutcome.Cancelled -> HelperOutcome.Cancelled
            StudioHelperTerminalOutcome.Unknown -> HelperOutcome.Unknown
        },
        answer,
        turn,
    )

    override fun toString(): String = "StudioHelperTerminal(turn=$turn, outcome=$outcome)"
}

/** A missing fingerprint is permitted only for a tombstone created before prompt arrival. */
@Serializable
internal data class StudioHelperReceipt(
    val request: RequestId,
    val phase: StudioHelperPhase,
    val fingerprint: String?,
    val session: SessionRef? = null,
    val turn: TurnId? = null,
    val terminal: StudioHelperTerminal? = null,
    /** Host ancestry captured before opening; never inferred from a parent's latest request. */
    val handoff: HelperHandoff? = null,
    /** Bound before admission IO; unlike session/turn, this is not evidence of native acceptance. */
    val preparedSession: SessionRef? = null,
    /** Unique sender claim; only that sender can prove its own native call was never entered. */
    val submissionClaim: String? = null,
) {
    init {
        require(fingerprint != null || phase == StudioHelperPhase.NotSubmitted)
        require(fingerprint != null || (handoff == null && preparedSession == null && submissionClaim == null))
        require(preparedSession == null || session == null || preparedSession == session)
        require(submissionClaim == null || (submissionClaim.isNotBlank() && phase != StudioHelperPhase.Preparing))
        require(
            phase in setOf(StudioHelperPhase.Preparing, StudioHelperPhase.NotSubmitted) ||
                handoff == null || preparedSession != null,
        )
        require((session == null) == (turn == null))
        require(phase != StudioHelperPhase.Accepted || session != null)
        require((phase == StudioHelperPhase.Terminal) == (terminal != null))
        require(terminal == null || (terminal.session == session && terminal.turn == turn))
        require(phase !in setOf(StudioHelperPhase.Preparing, StudioHelperPhase.NotSubmitted) || session == null)
    }

    val isUnresolved: Boolean get() = phase in setOf(
        StudioHelperPhase.Preparing,
        StudioHelperPhase.Submitting,
        StudioHelperPhase.Accepted,
    )

    fun requireNativeIdentity(session: SessionRef, turn: TurnId) {
        check(
            preparedSession == null || preparedSession == session,
        ) { "Native helper differs from its prepared session" }
        check(this.session == null || (this.session == session && this.turn == turn)) {
            "Native helper identity does not match its receipt"
        }
    }

    override fun toString(): String = "StudioHelperReceipt(request=$request, phase=$phase)"
}
