package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId

/** Validates observation transfers before any reader may disregard a retired witness. */
internal fun validateRecoveryLineage(records: Map<String, StudioRecoveryWitness>) {
    check(records.all { (key, witness) -> key == witness.execution.value }) { "Mismatched recovery witness key" }
    val successors = records.values.mapNotNull { it.observerSuccessor }
    check(successors.distinct().size == successors.size) { "Recovery observation has multiple predecessors" }
    for (start in records.values) {
        val visited = mutableSetOf<RequestId>()
        var current = start
        while (true) {
            check(visited.add(current.execution)) { "Cyclic recovery observation ownership" }
            val successor = current.observerSuccessor ?: break
            val next = checkNotNull(records[successor.value]) { "Recovery observation successor is missing" }
            check(current.phase in TransferableRecoveryPhases) { "A native send cannot transfer observation" }
            check(current.root == next.root && current.session == next.session && current.route == next.route) {
                "Recovery ownership context changed"
            }
            check(next.candidates.containsAll(current.candidates)) { "Recovery ownership lost native candidates" }
            check(
                current.observed == null || current.observed == next.observed,
            ) { "Recovery ownership lost native request" }
            check(current.turn == null || current.turn == next.turn) { "Recovery ownership lost native turn" }
            check(!current.isStopRequested || next.isStopRequested) { "Recovery ownership lost stop intent" }
            current = next
        }
    }
}

/**
 * Selects aliases and their complete ownership chains. Candidate membership fences uncertainty only. All callers
 * validate the journal first; a broken transfer must never make the old execution appear safely absent.
 */
internal fun recoveryStopChain(
    records: Map<String, StudioRecoveryWitness>,
    request: RequestId,
): List<StudioRecoveryWitness> {
    validateRecoveryLineage(records)
    fun leaf(start: StudioRecoveryWitness): RequestId {
        var current = start
        while (current.observerSuccessor != null) {
            current = checkNotNull(records[current.observerSuccessor.value])
        }
        return current.execution
    }
    val leaves = records.values.filter {
        it.execution == request || it.observed == request || it.inspectionTarget == request || it.root == request || (
            it.observed == null && it.phase != StudioRecoveryPhase.Submit && request in it.candidates
        )
    }.map(::leaf).toSet()
    return records.values.filter { leaf(it) in leaves }
}

/** Pure observation ownership transition; its caller writes the returned map under the shared journal lock. */
internal fun prepareRecoveryTransfer(
    records: Map<String, StudioRecoveryWitness>,
    receipts: Map<String, StudioHelperReceipt>,
    witness: StudioRecoveryWitness,
    isLegacyStopped: Boolean,
): Map<String, StudioRecoveryWitness> {
    val unresolved = records.activeRecoveryWitnesses(receipts).filter { it.blocksContext(receipts) }
    val old = unresolved.singleOrNull()
    check(unresolved.isEmpty() || old != null) { "Contradictory recovery observation ownership" }
    val prepared = if (old == null) {
        // Finishing R after an explicit Stop does not authorize a new observer to revive the same work root.
        witness.copy(
            isStopRequested = isLegacyStopped ||
                records.values.any { it.root == witness.root && it.isStopRequested },
        )
    } else if (old.hasNativeSend) {
        check(witness.predecessorExecution == old.execution) { "Recovery did not bind the previous native send" }
        witness.copy(isStopRequested = old.isStopRequested || isLegacyStopped)
    } else {
        check(old.phase in TransferableRecoveryPhases) { "A native send must be reconciled through its receipt" }
        check(old.root == witness.root && old.session == witness.session && old.route == witness.route) {
            "Recovery ownership context changed"
        }
        check(witness.candidates.containsAll(old.candidates)) { "Recovery ownership lost native candidates" }
        check(receipts[witness.execution.value] == null) { "Recovery successor already has a native send journal" }
        witness.copy(
            phase = old.phase,
            observed = old.observed,
            turn = old.turn,
            outcome = old.outcome,
            isStopRequested = old.isStopRequested || isLegacyStopped,
        )
    }
    val transferred = old?.takeUnless { it.hasNativeSend }?.let {
        records + (it.execution.value to it.copy(observerSuccessor = witness.execution))
    } ?: records
    return transferred + (witness.execution.value to prepared)
}
