package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId

/** A potential native head without a receipt remains uncertain; it never falls back to an older terminal source. */
internal data class StudioRecoveryWorkResolution(
    val nativeRequest: RequestId,
    val receipt: StudioHelperReceipt?,
    val observer: StudioRecoveryWitness?,
    val isConsistent: Boolean,
    val isStopRequested: Boolean = false,
)

internal fun resolveRecoveryWork(
    root: RequestId,
    receipts: Map<String, StudioHelperReceipt>,
    recoveries: Map<String, StudioRecoveryWitness>,
): StudioRecoveryWorkResolution {
    val active = recoveries.activeRecoveryWitnesses(receipts).filter { it.root == root }
    val chain = continuationChain(receipts, root)
    val contexts = recoveries.values.filter { it.root == root }
    val target = resolveNativeTarget(root, chain, contexts)
    requireWorkContexts(target, receipts, active, contexts)
    val candidates = active.filter {
        it.inspectionTarget == target || it.observed == target || (it.hasNativeSend && it.execution == target)
    }
    val live = candidates.filter { it.phase !in setOf(StudioRecoveryPhase.Terminal, StudioRecoveryPhase.NoSend) }
    check(live.size <= 1) { "Recovery work has competing observers" }
    val terminal = candidates.filter { it.phase == StudioRecoveryPhase.Terminal }
    check(terminal.map { Triple(it.observed, it.turn, it.outcome) }.distinct().size <= 1) {
        "Recovery work has contradictory terminal observations"
    }
    val observer = live.singleOrNull() ?: terminal.minByOrNull { it.execution.value }
    return StudioRecoveryWorkResolution(
        target,
        receipts[target.value],
        observer,
        live.isEmpty() || terminal.isEmpty(),
        chain.any { it.isContinuationFenced } || contexts.any { it.isStopRequested },
    )
}

private fun resolveNativeTarget(
    root: RequestId,
    chain: List<StudioHelperReceipt>,
    contexts: List<StudioRecoveryWitness>,
): RequestId {
    val linked = chain.mapTo(mutableSetOf()) { it.request }
    val missing = contexts.filter { it.hasNativeSend && it.execution !in linked }
    check(missing.size <= 1) { "Several native sends have missing receipts" }
    return missing.singleOrNull()?.execution
        ?: chain.lastOrNull { it.phase != StudioHelperPhase.NotSubmitted || it.graphContinuation == null }?.request
        ?: root
}

private fun requireWorkContexts(
    target: RequestId,
    receipts: Map<String, StudioHelperReceipt>,
    active: List<StudioRecoveryWitness>,
    contexts: List<StudioRecoveryWitness>,
) {
    val head = receipts[target.value]
    val bound = head?.session ?: head?.preparedSession
    val current = active.singleOrNull { it.inspectionTarget == target }
        ?: contexts.firstOrNull()?.let { historical ->
            if (bound != null && head?.nativeRoute != null) {
                historical.copy(session = bound, route = head.nativeRoute, inspectionTarget = target)
            } else {
                historical
            }
        }
    for (historical in contexts) {
        if (current != null && historical != current) requireRecoveryContextEdge(historical, current, receipts)
    }
}
