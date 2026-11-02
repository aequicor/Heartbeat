package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt

/** Called under the shared route/journal locks after fresh recovery selected this exact delivery's Submit. */
internal fun prepareGraphContinuationReceipt(
    records: Map<String, StudioHelperReceipt>,
    witness: StudioRecoveryWitness,
    request: StudioTurnRequest,
): StudioHelperPreparation {
    val delivery = checkNotNull(request.submission?.recoveryDelivery)
    check(witness.delivery == delivery && witness.phase == StudioRecoveryPhase.Submit) {
        "Graph continuation has no admitted recovery delivery"
    }
    check(witness.isInspectionCurrent && !witness.isStopRequested && witness.observerSuccessor == null) {
        "Graph continuation lost fresh native proof or observation ownership"
    }
    check(delivery.fingerprint == request.recoveryPayloadFingerprint()) { "Graph recovery payload changed" }
    val source = checkNotNull(witness.inspectionTarget) { "Graph continuation needs an exact native predecessor" }
    val chain = continuationChain(records, source)
    val previous = checkNotNull(chain.firstOrNull { it.request == source }) { "Graph predecessor is missing" }
    check(chain.none { it.isContinuationFenced }) { "Graph work was stopped" }
    val root = checkNotNull(records[witness.root.value]) { "Graph work root is missing" }
    val evidence = if (witness.isIdleConfirmed && witness.observed == null) {
        StudioGraphContinuationEvidence.FreshIdle
    } else {
        check(witness.observed == source && witness.outcome == StudioHelperTerminalOutcome.Cancelled) {
            "Graph continuation has no exact native cancellation"
        }
        StudioGraphContinuationEvidence.Cancelled
    }
    val proof = StudioGraphContinuationProof(
        source,
        request.request,
        witness.root,
        checkNotNull(root.fingerprint),
        delivery,
        witness.session,
        checkNotNull(witness.route),
        evidence,
    )
    val prompt = HelperPrompt(request.request, request.prompt, directives = request.directives)
    val receipt = StudioHelperReceipt(
        request.request, StudioHelperPhase.Preparing, prompt.fingerprint(),
        handoff = previous.handoff,
        isNativeEntryTracked = true,
        causes = previous.causes,
        policy = previous.policy,
        executionSnapshot = previous.executionSnapshot,
        continuationOf = source,
        graphContinuation = proof,
    )
    proof.requireSource(records, previous, receipt)
    val existing = records[request.request.value]
    if (existing != null) return retryGraphContinuation(records, existing, receipt)
    check(chain.last().request == source) { "Graph source already has a native successor" }
    check(records.unresolvedHeads().all { it.request == source }) { "Another native execution is unresolved" }
    return StudioHelperPreparation(receipt, true)
}

private fun retryGraphContinuation(
    records: Map<String, StudioHelperReceipt>,
    existing: StudioHelperReceipt,
    receipt: StudioHelperReceipt,
): StudioHelperPreparation {
    val original = checkNotNull(existing.graphContinuation)
    val proof = checkNotNull(receipt.graphContinuation)
    check(
        original.copy(delivery = proof.delivery, evidence = proof.evidence) == proof &&
            original.delivery.fingerprint == proof.delivery.fingerprint && existing.fingerprint == receipt.fingerprint,
    ) { "Graph native continuation changed after admission" }
    val isRetry = existing.phase == StudioHelperPhase.NotSubmitted && existing.isNativeEntryTracked
    check(!isRetry || records.unresolvedHeads().all { it.request == proof.source }) {
        "Another native execution is unresolved"
    }
    return if (isRetry) {
        StudioHelperPreparation(receipt.copy(graphContinuation = original), true)
    } else {
        StudioHelperPreparation(existing, false)
    }
}
