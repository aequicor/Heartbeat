package io.aequicor.heartbeat.feature.aistudio.impl.data

/** A delivery changes evidence, never its reserved context or monotonic send/stop ownership. */
internal fun StudioRecoveryWitness.requireSameReservation(changed: StudioRecoveryWitness) {
    check(changed.execution == execution && changed.root == root && changed.candidates == candidates) {
        "Recovery transition changed its work identity"
    }
    check(
        changed.session == session && changed.route == route && changed.inspectionTarget == inspectionTarget &&
            changed.predecessorExecution == predecessorExecution,
    ) { "Recovery transition changed its native context" }
    check(
        changed.delivery == delivery && changed.observerSuccessor == observerSuccessor &&
            (!isStopRequested || changed.isStopRequested) &&
            (!isNativeSendAuthorized || changed.isNativeSendAuthorized),
    ) {
        "Recovery transition changed ownership or cleared a fence"
    }
}
