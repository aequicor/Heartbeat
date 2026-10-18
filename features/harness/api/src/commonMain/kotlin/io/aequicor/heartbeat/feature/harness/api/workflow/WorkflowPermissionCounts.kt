package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.harness.api.HarnessId

/**
 * Live organism requests grouped by their harness for navigation badges. No request content or cell identity
 * leaves this projection. Suspended, cancelling and terminal runs never offer an actionable request.
 */
public fun HarnessRunsState.organismPermissionCounts(): Map<HarnessId, Int> {
    val ready = (this as? HarnessRunsState.Ready)?.takeUnless { it.isSuspended } ?: return emptyMap()
    return ready.runs.asSequence()
        .filter { it.status == WorkflowStatus.Running && it.cancellation == null && it.organismRequests.isNotEmpty() }
        .groupBy { it.harness }
        .mapValues { (_, runs) -> runs.sumOf { it.organismRequests.size } }
}
