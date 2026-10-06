package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
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
) {
    init {
        require(fingerprint != null || phase == StudioHelperPhase.NotSubmitted)
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
        check(this.session == null || (this.session == session && this.turn == turn)) {
            "Native helper identity does not match its receipt"
        }
    }

    override fun toString(): String = "StudioHelperReceipt(request=$request, phase=$phase)"
}
