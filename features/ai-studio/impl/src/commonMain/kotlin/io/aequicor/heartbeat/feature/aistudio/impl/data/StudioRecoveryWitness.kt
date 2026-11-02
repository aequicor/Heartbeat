package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** Observation of older native work is separate from permission to send the recovery execution. */
@Serializable
internal enum class StudioRecoveryPhase { Inspecting, Submit, Observing, Terminal, Unknown }

/**
 * Durable correlation for execution Q observing an older R. This is neither an Accepted Q receipt nor a fabricated
 * send receipt for R. The expected native session and candidate requests come from trusted host recovery state.
 * A stop before observation remains pending until the host resolves the native identity or proves a new send safe.
 */
@Serializable
internal data class StudioRecoveryWitness(
    val execution: RequestId,
    val root: RequestId,
    val candidates: Set<RequestId>,
    val session: SessionRef,
    val phase: StudioRecoveryPhase = StudioRecoveryPhase.Inspecting,
    val observed: RequestId? = null,
    val turn: TurnId? = null,
    val outcome: StudioHelperTerminalOutcome? = null,
    val isStopRequested: Boolean = false,
    /** Observation ownership only; never a native continuation or permission to replay this execution. */
    val observerSuccessor: RequestId? = null,
) {
    init {
        require(candidates.isNotEmpty() && root in candidates)
        require(observerSuccessor != execution)
        require(observerSuccessor == null || phase in TransferableRecoveryPhases)
        require(observed == null || observed in candidates)
        require(turn == null || observed != null)
        require(outcome == null || observed != null)
        require(phase != StudioRecoveryPhase.Inspecting || observed == null)
        require(phase != StudioRecoveryPhase.Observing || (observed != null && turn != null && outcome == null))
        require(phase != StudioRecoveryPhase.Terminal || outcome in RecoveryKnownOutcomes)
        require(
            phase != StudioRecoveryPhase.Submit || outcome == null || outcome == StudioHelperTerminalOutcome.Cancelled,
        )
    }

    /** A stop never proves native completion and cannot be cleared by a replay or a new observation. */
    fun stopped(): StudioRecoveryWitness = copy(isStopRequested = true)

    /** Only the host's native inspection may select Submit; this does not assert that R was never submitted. */
    fun submitting(): StudioRecoveryWitness {
        if (isStopRequested) throw CancellationException("Recovery was stopped before new submission")
        check(
            phase in setOf(StudioRecoveryPhase.Inspecting, StudioRecoveryPhase.Submit) ||
                (phase == StudioRecoveryPhase.Terminal && outcome == StudioHelperTerminalOutcome.Cancelled),
        ) { "Unresolved native recovery cannot authorize a new send" }
        return copy(phase = StudioRecoveryPhase.Submit)
    }

    /** Native evidence may enrich a terminal-only observation, but cannot change its request, turn or outcome. */
    fun observed(
        request: RequestId,
        nativeSession: SessionRef,
        nativeTurn: TurnId?,
        nativeOutcome: StudioHelperTerminalOutcome? = null,
    ): StudioRecoveryWitness {
        check(phase != StudioRecoveryPhase.Submit) { "A new-send decision cannot adopt an older turn" }
        check(nativeSession == session && request in candidates) { "Native recovery belongs to another context" }
        check(observed == null || observed == request) { "Native recovery request changed" }
        check(turn == null || nativeTurn == null || turn == nativeTurn) { "Native recovery turn changed" }
        check(
            outcome == null || outcome == StudioHelperTerminalOutcome.Unknown || nativeOutcome == null ||
                outcome == nativeOutcome,
        ) { "Confirmed native outcome changed" }
        val settled = outcome?.takeIf { it in RecoveryKnownOutcomes } ?: nativeOutcome
        val actualTurn = turn ?: nativeTurn
        require(actualTurn != null || settled != null) { "Native observation has no turn or outcome" }
        return copy(
            observed = request,
            turn = actualTurn,
            outcome = settled,
            phase = when (settled) {
                null -> StudioRecoveryPhase.Observing

                StudioHelperTerminalOutcome.Unknown -> StudioRecoveryPhase.Unknown

                StudioHelperTerminalOutcome.Completed, StudioHelperTerminalOutcome.Failed,
                StudioHelperTerminalOutcome.Cancelled,
                -> StudioRecoveryPhase.Terminal
            },
        )
    }

    /** Lost observation retains every known native identity and all stop intent. */
    fun unknown(): StudioRecoveryWitness = if (phase == StudioRecoveryPhase.Terminal) {
        this
    } else {
        check(phase != StudioRecoveryPhase.Submit) { "A new send is tracked by its own receipt" }
        copy(phase = StudioRecoveryPhase.Unknown)
    }

    override fun toString(): String = "StudioRecoveryWitness(phase=$phase, stop=$isStopRequested)"
}

private val RecoveryKnownOutcomes = setOf(
    StudioHelperTerminalOutcome.Completed,
    StudioHelperTerminalOutcome.Failed,
    StudioHelperTerminalOutcome.Cancelled,
)

/** Submit delegates to Q's send receipt; a missing receipt still bars context replacement. */
internal fun StudioRecoveryWitness.blocksContext(receipts: Map<String, StudioHelperReceipt>): Boolean =
    observerSuccessor == null && when (phase) {
        StudioRecoveryPhase.Terminal -> false
        StudioRecoveryPhase.Submit -> execution.value !in receipts
        StudioRecoveryPhase.Inspecting, StudioRecoveryPhase.Observing, StudioRecoveryPhase.Unknown -> true
    }

/** New sends cannot bypass an unresolved observation or revive its explicit stop. Called under the journal lock. */
internal fun requireRecoveryAdmission(
    recoveries: Collection<StudioRecoveryWitness>,
    receipts: Map<String, StudioHelperReceipt>,
    request: RequestId,
) {
    val root = recoveries.singleOrNull { it.execution == request }?.root
        ?: (receipts[request.value]?.policy as? StudioAttemptPolicy.GraphWork)?.recoveryRoot
    val isRevoked = recoveries.any {
        (it.execution == request && (it.isStopRequested || it.observerSuccessor != null)) ||
            (root != null && it.root == root && it.isStopRequested)
    }
    if (isRevoked) {
        throw CancellationException("Recovery was stopped or its observation ownership was transferred")
    }
    check(
        recoveries.filter { it.observerSuccessor == null }.all {
            it.phase == StudioRecoveryPhase.Terminal || (
                it.phase == StudioRecoveryPhase.Submit &&
                    (it.execution == request || receipts[it.execution.value]?.isUnresolved == false)
            )
        },
    ) { "Native recovery must be reconciled before another send" }
}

internal val TransferableRecoveryPhases = setOf(
    StudioRecoveryPhase.Inspecting,
    StudioRecoveryPhase.Observing,
    StudioRecoveryPhase.Unknown,
)
