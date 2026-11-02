package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId

/** Both journals are fenced under their common mutex before cancellation performs any native IO. */
internal data class StudioRecoveryStopPlan(
    val recoveries: Map<String, StudioRecoveryWitness>,
    val receipts: Map<String, StudioHelperReceipt>,
    val targets: List<StudioRecoveryWitness>,
)

internal fun recoveryStopPlan(
    records: Map<String, StudioRecoveryWitness>,
    receipts: Map<String, StudioHelperReceipt>,
    request: RequestId,
): StudioRecoveryStopPlan {
    validateRecoveryWorkLineage(records, receipts)
    val roots = recoveryStopChain(records, request, receipts).mapTo(mutableSetOf()) { it.root }
    (receipts[request.value]?.policy as? StudioAttemptPolicy.GraphWork)?.let { roots.add(it.recoveryRoot) }
    val targets = records.values.filter { it.root in roots || it.root == request }.map { it.stopped() }
    val changed = records + targets.associateBy { it.execution.value }
    var stopped = receipts
    for (witness in targets) {
        val native = if (witness.hasNativeSend) witness.execution else witness.inspectionTarget ?: witness.observed
        if (native != null && native.value in stopped) {
            stopped = stopContinuationChain(stopped, native, shouldStopNative = true).first
        }
    }
    val active = changed.activeRecoveryWitnesses(stopped).filter { it.root in roots || it.root == request }
    return StudioRecoveryStopPlan(changed, stopped, active)
}
