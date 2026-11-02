package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** Observation of older native work is separate from permission to send the recovery execution. */
@Serializable
internal enum class StudioRecoveryPhase { Inspecting, Submit, Observing, Terminal, Unknown, NoSend }

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
    /** Proof concerns this delivery only, even when execution and historical native request ids are equal. */
    val delivery: StudioRecoveryDelivery? = null,
    val refusal: StudioRecoveryRefusal? = null,
    val isIdleConfirmed: Boolean = false,
    /** Fresh native evidence for [delivery], cleared on rearm or loss; old evidence never authorizes its retry. */
    val isInspectionCurrent: Boolean = false,
    /** Native resume route, including its model; a later new turn has a separate settings snapshot. */
    val route: StudioRecoveryRoute? = null,
    /** Exact native head resolved from immutable work edges; candidate membership alone cannot select it. */
    val inspectionTarget: RequestId? = null,
    /** Prior execution that sent the inspected native work; separate from observation ownership transfer. */
    val predecessorExecution: RequestId? = null,
    /** Never cleared by observation restart: this execution may already have crossed native entry. */
    val isNativeSendAuthorized: Boolean = false,
    /** Exact NoSend proof retired the send authorization; rearm must inspect its source before authorizing anew. */
    val isSendRetired: Boolean = false,
    /** Historical inspection authorizing the send is retained when this execution later observes itself. */
    val submittedSource: StudioRecoverySource? = null,
) {
    init {
        require(candidates.isNotEmpty() && root in candidates)
        require(observerSuccessor != execution)
        require(observerSuccessor == null || phase in TransferableRecoveryPhases)
        require(delivery == null || delivery.execution == execution)
        require(refusal == null || phase == StudioRecoveryPhase.NoSend)
        require(
            phase != StudioRecoveryPhase.NoSend || (
                delivery != null && refusal != null && isInspectionCurrent &&
                    ((isIdleConfirmed && observed == null) || outcome == StudioHelperTerminalOutcome.Cancelled)
            ),
        )
        require(observed == null || observed in candidates || observed == inspectionTarget)
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

    /** A replay never changes native context, candidate provenance or the meaning of a closed delivery. */
    fun repeatDelivery(
        incoming: StudioRecoveryWitness,
        receipts: Map<String, StudioHelperReceipt> = emptyMap(),
    ): StudioRecoveryWitness {
        check(
            execution == incoming.execution && root == incoming.root &&
                incoming.candidates.containsAll(candidates),
        ) {
            "Recovery execution identity changed"
        }
        check(observerSuccessor == null) { "Recovery observation ownership was transferred" }
        val transfer = recoveryObservationTransfer(this, incoming, receipts)
        if (transfer == StudioObservationTransfer.ReceiptContinuation) {
            check(delivery != incoming.delivery) { "A reserved delivery cannot change its native target" }
            requireSamePayload(checkNotNull(incoming.delivery))
            return copy(
                session = incoming.session,
                route = incoming.route,
                inspectionTarget = incoming.inspectionTarget,
                predecessorExecution = incoming.predecessorExecution,
                refusal = null,
                delivery = incoming.delivery,
                candidates = incoming.candidates,
                phase = StudioRecoveryPhase.Inspecting,
                observed = null,
                turn = null,
                outcome = null,
                isIdleConfirmed = false,
                isInspectionCurrent = false,
            )
        }
        return when {
            delivery == incoming.delivery -> {
                check(candidates == incoming.candidates) { "A reserved recovery delivery cannot change candidates" }
                this
            }

            phase == StudioRecoveryPhase.NoSend ->
                rearm(checkNotNull(incoming.delivery)).copy(candidates = incoming.candidates)

            hasNativeSend -> {
                requireSamePayload(checkNotNull(incoming.delivery))
                reobserveNativeSend(incoming)
            }

            phase in TransferableRecoveryPhases -> {
                val next = checkNotNull(incoming.delivery)
                requireSamePayload(next)
                copy(
                    delivery = next,
                    candidates = incoming.candidates,
                    isIdleConfirmed = false,
                    isInspectionCurrent = false,
                )
            }

            else -> error("Recovery delivery requires explicit reconciliation")
        }
    }

    /** Fresh idle proof cannot erase a known unresolved native binding or an already-started new send. */
    fun idle(nativeSession: SessionRef): StudioRecoveryWitness {
        check(nativeSession == session && observerSuccessor == null) { "Recovery idle proof belongs to another owner" }
        check(phase in TransferableRecoveryPhases || phase == StudioRecoveryPhase.Terminal) {
            "This recovery requires exact native reconciliation"
        }
        check(observed == null || outcome == StudioHelperTerminalOutcome.Cancelled) {
            "Idle alone cannot retire a known native execution"
        }
        return copy(
            phase = if (observed == null) StudioRecoveryPhase.Inspecting else StudioRecoveryPhase.Terminal,
            isIdleConfirmed = true,
            isInspectionCurrent = true,
        )
    }

    /** Closes only the new-send path of this exact delivery, without inventing native NotSubmitted evidence. */
    fun noSend(proof: StudioRecoveryNoSendProof, isNewSendRevoked: Boolean = false): StudioRecoveryWitness {
        check(delivery == proof.delivery && observerSuccessor == null) { "NoSend proof belongs to another delivery" }
        check(refusal == null || refusal == proof.refusal) { "A closed delivery cannot change its refusal" }
        check(phase != StudioRecoveryPhase.Submit || isNewSendRevoked) {
            "An admitted new send needs its own sender proof"
        }
        check(isInspectionCurrent) { "Closing recovery needs current native inspection" }
        check((isIdleConfirmed && observed == null) || outcome == StudioHelperTerminalOutcome.Cancelled) {
            "Closing preparation cannot discard unresolved native ownership"
        }
        return copy(
            phase = StudioRecoveryPhase.NoSend,
            refusal = proof.refusal,
            isSendRetired = isSendRetired || (isNewSendRevoked && hasNativeSend),
            isStopRequested = isStopRequested || proof.refusal == StudioRecoveryRefusal.Dropped,
        )
    }

    /** Only a deferred delivery may rearm; it keeps identity/stop and discards all old permission to submit. */
    fun rearm(next: StudioRecoveryDelivery): StudioRecoveryWitness {
        check(phase == StudioRecoveryPhase.NoSend && refusal == StudioRecoveryRefusal.Deferred) {
            "Only an exactly deferred recovery delivery may retry"
        }
        check(observerSuccessor == null && !isStopRequested) { "Stopped recovery cannot rearm" }
        requireSamePayload(next)
        val previous = checkNotNull(delivery)
        check(next.chat == previous.chat && next.execution == execution && next.claim != previous.claim) {
            "Recovery retry needs a fresh reservation for the same execution"
        }
        return copy(
            phase = if (observed == null) StudioRecoveryPhase.Inspecting else StudioRecoveryPhase.Unknown,
            delivery = next,
            refusal = null,
            isIdleConfirmed = false,
            isInspectionCurrent = false,
        )
    }

    /** A new coordinator claim may resume observation, never replace the saved payload or provenance. */
    private fun requireSamePayload(next: StudioRecoveryDelivery) {
        val previous = checkNotNull(delivery) { "Legacy recovery needs explicit payload reconciliation" }
        val fingerprint = checkNotNull(previous.fingerprint) { "Legacy recovery needs explicit payload reconciliation" }
        check(next.chat == previous.chat && next.execution == execution && next.fingerprint == fingerprint) {
            "Recovery delivery changed its payload"
        }
    }

    /** Only the host's native inspection may select Submit; this does not assert that R was never submitted. */
    fun submitting(): StudioRecoveryWitness {
        if (isStopRequested) throw CancellationException("Recovery was stopped before new submission")
        check(
            observed != execution && inspectionTarget != execution,
        ) { "An observed native request cannot be submitted again under the same identity" }
        check(delivery == null || isInspectionCurrent) { "A new delivery must inspect native state before sending" }
        check(
            phase in setOf(StudioRecoveryPhase.Inspecting, StudioRecoveryPhase.Submit) ||
                (phase == StudioRecoveryPhase.Terminal && outcome == StudioHelperTerminalOutcome.Cancelled),
        ) { "Unresolved native recovery cannot authorize a new send" }
        return copy(
            phase = StudioRecoveryPhase.Submit,
            isNativeSendAuthorized = true,
            isSendRetired = false,
            submittedSource = inspectionTarget?.let {
                StudioRecoverySource(it, turn, outcome, isIdleConfirmed)
            },
        )
    }

    /** Native evidence may enrich a terminal-only observation, but cannot change its request, turn or outcome. */
    fun observed(
        request: RequestId,
        nativeSession: SessionRef,
        nativeTurn: TurnId?,
        nativeOutcome: StudioHelperTerminalOutcome? = null,
    ): StudioRecoveryWitness {
        check(phase !in setOf(StudioRecoveryPhase.Submit, StudioRecoveryPhase.NoSend)) {
            "A new-send decision or closed delivery cannot adopt an older turn"
        }
        requireInspectionTarget(request, nativeSession)
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
            isIdleConfirmed = false,
            isInspectionCurrent = true,
            phase = when (settled) {
                null -> StudioRecoveryPhase.Observing

                StudioHelperTerminalOutcome.Unknown -> StudioRecoveryPhase.Unknown

                StudioHelperTerminalOutcome.Completed, StudioHelperTerminalOutcome.Failed,
                StudioHelperTerminalOutcome.Cancelled,
                -> StudioRecoveryPhase.Terminal
            },
        )
    }

    private fun requireInspectionTarget(request: RequestId, nativeSession: SessionRef) {
        check(nativeSession == session && (request in candidates || request == inspectionTarget)) {
            "Native recovery belongs to another context"
        }
        check(inspectionTarget == null || request == inspectionTarget) { "Recovery inspected a historical native turn" }
    }

    /** Lost observation retains every known native identity and all stop intent. */
    fun unknown(): StudioRecoveryWitness = if (phase in setOf(
            StudioRecoveryPhase.Terminal,
            StudioRecoveryPhase.NoSend,
        )
    ) {
        this
    } else {
        check(phase != StudioRecoveryPhase.Submit) { "A new send is tracked by its own receipt" }
        copy(phase = StudioRecoveryPhase.Unknown, isIdleConfirmed = false, isInspectionCurrent = false)
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
        StudioRecoveryPhase.Terminal, StudioRecoveryPhase.NoSend -> false
        StudioRecoveryPhase.Submit -> execution.value !in receipts
        StudioRecoveryPhase.Inspecting, StudioRecoveryPhase.Observing, StudioRecoveryPhase.Unknown -> true
    }

/** New sends cannot bypass an unresolved observation or revive its explicit stop. Called under the journal lock. */
internal fun requireRecoveryAdmission(
    recoveries: Collection<StudioRecoveryWitness>,
    receipts: Map<String, StudioHelperReceipt>,
    request: RequestId,
) {
    val unresolved = receipts.unresolvedHeads().mapTo(mutableSetOf()) { it.request }
    val root = recoveries.singleOrNull { it.execution == request }?.root
        ?: (receipts[request.value]?.policy as? StudioAttemptPolicy.GraphWork)?.recoveryRoot
    val isRevoked = recoveries.any {
        (
            it.execution == request &&
                (it.isStopRequested || it.observerSuccessor != null || it.phase == StudioRecoveryPhase.NoSend)
        ) ||
            (root != null && it.root == root && it.isStopRequested)
    }
    if (isRevoked) {
        throw CancellationException("Recovery was stopped or its observation ownership was transferred")
    }
    check(
        recoveries.associateBy { it.execution.value }.activeRecoveryWitnesses(receipts).all {
            it.phase in setOf(StudioRecoveryPhase.Terminal, StudioRecoveryPhase.NoSend) || (
                it.phase == StudioRecoveryPhase.Submit &&
                    (it.execution == request || (it.execution.value in receipts && it.execution !in unresolved))
            )
        },
    ) { "Native recovery must be reconciled before another send" }
}

internal val TransferableRecoveryPhases = setOf(
    StudioRecoveryPhase.Inspecting,
    StudioRecoveryPhase.Observing,
    StudioRecoveryPhase.Unknown,
)

/** Frozen native resume target and workspace; future model choices must not reconfigure observed work. */
@Serializable
internal data class StudioRecoveryRoute(val target: EngineTarget, val workspace: WorkspaceRef?)
