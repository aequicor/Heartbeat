package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import kotlinx.serialization.Serializable

/** Immutable host claim persisted before opening a native context or submitting its prompt. */
@Serializable
internal data class OrganicNativeClaim(
    val token: String,
    val address: String,
    val execution: SessionExecutionBinding?,
    val target: EngineTarget,
    val workspace: WorkspaceRef?,
    val request: RequestId,
    val prompt: String,
    val inputs: List<ResourceRef> = emptyList(),
) {
    init {
        require(token.isNotBlank()) { "Native attempt claim is missing" }
        require(OrganicExecutorAddress.parse(address).instance().id == address) { "Noncanonical native address" }
        require(execution == null || execution.instance.id == address) { "Native claim address differs from binding" }
    }
}

/** Create and send have independent uncertain boundaries; neither acknowledgement proves native termination. */
@Serializable
internal enum class OrganicNativeStage { Reserved, Opening, Opened, Entered, Sending, Accepted, Settled }

/** Durable entry acknowledgements cannot be replayed as permission for another native RPC. */
internal val OrganicNativeStage.isAdmission: Boolean
    get() = this == OrganicNativeStage.Opening || this == OrganicNativeStage.Entered ||
        this == OrganicNativeStage.Sending

/** Quiescence has an explicit proof source; idle never manufactures the result of an earlier request. */
@Serializable
internal enum class OrganicNativeEnd { NotEntered, NotSubmitted, NativeTerminal, NativeIdle, ConfirmedStop }

/** Exact native correlation and cleanup evidence of one immutable claim. */
@Serializable
internal data class OrganicNativeAttempt(
    val claim: OrganicNativeClaim,
    val stage: OrganicNativeStage = OrganicNativeStage.Reserved,
    val session: SessionRef? = null,
    val turn: TurnId? = null,
    val outcome: TurnOutcome? = null,
    val end: OrganicNativeEnd? = null,
    val revision: Long = 0,
    val prepared: PromptRequest? = null,
    val answer: String? = null,
) {
    val isQuiescent: Boolean get() = end != null

    init {
        require(
            stage !in setOf(
                OrganicNativeStage.Opened,
                OrganicNativeStage.Entered,
                OrganicNativeStage.Sending,
                OrganicNativeStage.Accepted,
            ) || session != null,
        ) {
            "Opened native attempt has no session"
        }
        require(stage != OrganicNativeStage.Accepted || turn != null) { "Accepted attempt has no native turn" }
        require(answer == null || outcome == TurnOutcome.Completed) { "Only a completed request has a final answer" }
        require(prepared == null || prepared.id == claim.request) { "Prepared request identity changed" }
        require(revision >= 0) { "Invalid native receipt revision" }
        require(session == null || session.engine == claim.target.engine) { "Native receipt engine mismatch" }
        require(turn == null || session != null) { "Native turn has no session" }
        require(outcome == null || (turn != null && isQuiescent)) { "Outcome needs exact terminal evidence" }
        require((end != null) == (stage == OrganicNativeStage.Settled)) { "Settlement needs quiescence evidence" }
        when (end) {
            OrganicNativeEnd.NotEntered -> require(session == null && outcome == null) {
                "No-entry proof has native work"
            }

            OrganicNativeEnd.NotSubmitted -> require(session != null && turn == null && outcome == null) {
                "No-submit proof belongs to an opened context without a turn"
            }

            OrganicNativeEnd.NativeTerminal -> require(
                turn != null && outcome != null && outcome != TurnOutcome.Unknown,
            ) {
                "Terminal proof requires a known native result"
            }

            OrganicNativeEnd.NativeIdle -> require(session != null && outcome == null) {
                "Idle cannot prove a result"
            }

            OrganicNativeEnd.ConfirmedStop -> require(session != null && turn != null) {
                "Stop proof lacks correlation"
            }

            null -> Unit
        }
    }
}

/**
 * Attempt portion of the canonical organism journal. Snapshot writes preserve it and its mutations preserve the
 * lifecycle snapshot. A late callback may enrich its own receipt, never replace a claim or authorize another send.
 */
internal interface OrganicNativeJournal {
    /** True only when every native entry of this record has always required a durable claim. */
    suspend fun isTracked(id: OrganismId, incarnation: String): Boolean
    suspend fun attempts(id: OrganismId, incarnation: String): List<OrganicNativeAttempt>
    suspend fun reserve(id: OrganismId, incarnation: String, claim: OrganicNativeClaim): OrganicNativeAttempt
    suspend fun advance(
        id: OrganismId,
        incarnation: String,
        expected: OrganicNativeAttempt,
        next: OrganicNativeAttempt,
    ): OrganicNativeAttempt

    /** Caller has fenced and drained admission and supplied every known native ref; no absence-based proof. */
    suspend fun released(id: OrganismId, incarnation: String, version: Long, references: Set<SessionRef>)
}

/** Only monotonic native evidence can advance a claim; uncertain creation cannot be settled by absence. */
internal fun OrganicNativeAttempt.requireProgress(next: OrganicNativeAttempt) {
    requireIdentity(next)
    check(!this.isQuiescent) { "A settled native attempt cannot restart" }
    val allowed = when (stage) {
        OrganicNativeStage.Reserved -> setOf(OrganicNativeStage.Opening, OrganicNativeStage.Settled)

        OrganicNativeStage.Opening -> setOf(OrganicNativeStage.Opened, OrganicNativeStage.Settled)

        OrganicNativeStage.Opened -> setOf(OrganicNativeStage.Entered, OrganicNativeStage.Settled)

        OrganicNativeStage.Entered -> setOf(
            OrganicNativeStage.Sending,
            OrganicNativeStage.Accepted,
            OrganicNativeStage.Settled,
        )

        OrganicNativeStage.Sending -> setOf(OrganicNativeStage.Accepted, OrganicNativeStage.Settled)

        OrganicNativeStage.Accepted -> setOf(OrganicNativeStage.Settled)

        OrganicNativeStage.Settled -> emptySet()
    }
    check(next.stage in allowed) { "Invalid native progress transition" }
    check(next.end != OrganicNativeEnd.NotSubmitted || stage == OrganicNativeStage.Opened) {
        "No-submit proof cannot cross a native send boundary"
    }
    check(next.end != OrganicNativeEnd.NotEntered || stage == OrganicNativeStage.Reserved) {
        "No-entry proof cannot cross a native opening boundary"
    }
    check(!next.isQuiescent || this.stage == OrganicNativeStage.Reserved || next.session != null) {
        "Missing native create acknowledgement is not quiescence evidence"
    }
}

private fun OrganicNativeAttempt.requireIdentity(next: OrganicNativeAttempt) {
    check(this.session == null || next.session == this.session) { "Native session cannot change" }
    check(this.prepared == null || next.prepared == this.prepared) { "Prepared payload cannot change" }
    check(this.turn == null || next.turn == this.turn) { "Native turn cannot change" }
}
