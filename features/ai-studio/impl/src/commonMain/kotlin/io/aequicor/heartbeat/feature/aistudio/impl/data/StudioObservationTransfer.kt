package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId

/** Evidence can move unchanged only while observing the same actual native request. */
internal enum class StudioObservationTransfer { SameNativeTarget, ReceiptContinuation }

internal fun recoveryObservationTransfer(
    previous: StudioRecoveryWitness,
    incoming: StudioRecoveryWitness,
    receipts: Map<String, StudioHelperReceipt>,
): StudioObservationTransfer {
    val source = previous.nativeObservationTarget(receipts)
    val target = incoming.inspectionTarget ?: incoming.observed ?: incoming.root
    if (source == target) {
        check(previous.session == incoming.session && previous.route == incoming.route) {
            "Native observation context changed without a continuation"
        }
        return StudioObservationTransfer.SameNativeTarget
    }
    val chain = continuationChain(receipts, source)
    val start = chain.indexOfFirst { it.request == source }
    val end = chain.indexOfFirst { it.request == target }
    check(start >= 0 && end > start) { "Observation target has no forward receipt ancestry" }
    requireRecoveryContextEdge(previous, incoming, receipts)
    return StudioObservationTransfer.ReceiptContinuation
}

/** An initial sender observed its predecessor; after entry the sender's own receipt supersedes that evidence. */
internal fun StudioRecoveryWitness.nativeObservationTarget(receipts: Map<String, StudioHelperReceipt>): RequestId {
    val inspected = inspectionTarget ?: observed ?: root
    if (!hasNativeSend) return inspected
    val chain = continuationChain(receipts, execution)
    val own = chain.indexOfFirst { it.request == execution }
    val target = chain.indexOfFirst { it.request == inspected }
    return if (own >= 0 && target >= own) inspected else execution
}
