package io.aequicor.heartbeat.feature.aistudio.impl.data

internal val StudioRecoveryWitness.hasNativeSend: Boolean
    get() = (isNativeSendAuthorized && !isSendRetired) || phase == StudioRecoveryPhase.Submit

/** A new delivery of a possibly sent request always observes its effective native head, never replays its send. */
internal fun StudioRecoveryWitness.reobserveNativeSend(incoming: StudioRecoveryWitness): StudioRecoveryWitness {
    val target = checkNotNull(incoming.inspectionTarget)
    return copy(
        phase = StudioRecoveryPhase.Inspecting,
        session = incoming.session,
        route = incoming.route,
        delivery = incoming.delivery,
        candidates = incoming.candidates,
        inspectionTarget = target,
        observed = null,
        turn = null,
        outcome = null,
        isIdleConfirmed = false,
        isInspectionCurrent = false,
        refusal = null,
        isNativeSendAuthorized = true,
        submittedSource = submittedSource ?: inspectionTarget?.let {
            StudioRecoverySource(it, turn, outcome, isIdleConfirmed)
        },
    )
}

/** Selects a native inspection target only from receipt ancestry, never from candidate or map ordering. */
internal fun StudioRecoveryWitness.withNativeWorkHead(
    recoveries: Map<String, StudioRecoveryWitness>,
    receipts: Map<String, StudioHelperReceipt>,
): StudioRecoveryWitness {
    val chain = continuationChain(receipts, root)
    val head = chain.lastOrNull { it.phase != StudioHelperPhase.NotSubmitted || it.graphContinuation == null }
    if (head != null) {
        val policy = head.policy
        check(if (policy is StudioAttemptPolicy.GraphWork) policy.recoveryRoot == root else chain.size == 1) {
            "Recovery root belongs to other work"
        }
        check(head.nativeRoute == null || head.nativeRoute == route) { "Recovery head launch route changed" }
        check((head.session ?: head.preparedSession)?.let { it == session } != false) {
            "Recovery head belongs to another native context"
        }
    }
    val linked = chain.mapTo(mutableSetOf()) { it.request }
    val missing = recoveries.values.filter { it.root == root && it.hasNativeSend && it.execution !in linked }
    check(missing.size <= 1) { "Several native sends have missing receipts" }
    val uncertain = missing.singleOrNull()
    val prior = chain.lastOrNull {
        it.phase != StudioHelperPhase.NotSubmitted && recoveries[it.request.value]?.hasNativeSend == true
    }
    return copy(
        inspectionTarget = uncertain?.execution ?: head?.request ?: root,
        predecessorExecution = (uncertain?.execution ?: prior?.request)?.takeUnless { it == execution },
    )
}

/** Native-send ancestry is separate from observation transfers; both must describe the same work and route. */
internal fun validateRecoveryWorkLineage(
    recoveries: Map<String, StudioRecoveryWitness>,
    receipts: Map<String, StudioHelperReceipt>,
) {
    validateRecoveryLineage(recoveries, receipts)
    for (start in recoveries.values) {
        val visited = mutableSetOf(start.execution)
        var current = start
        while (current.predecessorExecution != null) {
            val predecessor = checkNotNull(recoveries[current.predecessorExecution.value]) {
                "Recovery native predecessor is missing"
            }
            check(visited.add(predecessor.execution)) { "Cyclic recovery native ancestry" }
            check(predecessor.hasNativeSend) { "Recovery predecessor never authorized a send" }
            check(predecessor.root == current.root) { "Recovery native work changed its root" }
            requireRecoveryContextEdge(predecessor, current, receipts)
            val chain = continuationChain(receipts, predecessor.execution)
            val isMissingSend = predecessor.execution.value !in receipts &&
                current.inspectionTarget == predecessor.execution
            check(isMissingSend || chain.any { it.request == current.inspectionTarget }) {
                "Recovery lost its native work edge"
            }
            current = predecessor
        }
    }
}

/** Historical send authorizations remain durable but do not compete with their currently owning observer. */
internal fun Map<String, StudioRecoveryWitness>.activeRecoveryWitnesses(
    receipts: Map<String, StudioHelperReceipt>,
): List<StudioRecoveryWitness> {
    validateRecoveryWorkLineage(this, receipts)
    val followed = values.mapNotNullTo(mutableSetOf()) { it.predecessorExecution }
    return values.filter {
        it.observerSuccessor == null && it.execution !in followed && !it.isObservationRevoked(receipts)
    }
}

/** Only an immutable virtual receipt edge with its own native binding permits a context handoff. */
internal fun requireRecoveryContextEdge(
    previous: StudioRecoveryWitness,
    current: StudioRecoveryWitness,
    receipts: Map<String, StudioHelperReceipt>,
) {
    if (previous.session == current.session && previous.route == current.route) return
    val source = previous.nativeObservationTarget(receipts)
    val chain = continuationChain(receipts, source)
    val start = chain.indexOfFirst { it.request == source }
    val end = chain.indexOfFirst { it.request == current.inspectionTarget }
    check(start >= 0 && end > start) { "Recovery context has no native continuation ancestry" }
    val path = chain.subList(start + 1, end + 1)
    check(path.any { it.graphContinuation == null }) { "Graph retry cannot replace its native context" }
    val head = path.last()
    check((head.session ?: head.preparedSession) == current.session && head.nativeRoute == current.route) {
        "Recovery continuation lacks its exact native launch route"
    }
    checkNotNull(head.nativeRoute) { "Legacy continuation route requires reconciliation" }
}

/** A final exact pre-entry revocation ends observation ownership without inventing a native terminal event. */
internal fun StudioRecoveryWitness.isObservationRevoked(receipts: Map<String, StudioHelperReceipt>): Boolean {
    val target = receipts[inspectionTarget?.value] ?: return false
    return phase in TransferableRecoveryPhases && observed == null && target.phase == StudioHelperPhase.NotSubmitted &&
        target.isFinal && target.isNativeEntryTracked && target.nativeEntryClaim == null
}
