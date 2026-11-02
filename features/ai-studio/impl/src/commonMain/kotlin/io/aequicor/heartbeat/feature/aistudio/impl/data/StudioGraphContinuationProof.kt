package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.Serializable

/** Fresh Idle supersedes native ownership without inventing a terminal result for its predecessor. */
@Serializable
internal enum class StudioGraphContinuationEvidence { Cancelled, FreshIdle }

/** Host-journaled evidence for one native work edge, separate from virtual worktree action authorization. */
@Serializable
internal data class StudioGraphContinuationProof(
    val source: RequestId,
    val child: RequestId,
    val root: RequestId,
    val rootFingerprint: String,
    val delivery: StudioRecoveryDelivery,
    val session: SessionRef,
    val route: StudioRecoveryRoute,
    val evidence: StudioGraphContinuationEvidence,
) {
    override fun toString(): String = "StudioGraphContinuationProof(***)"
}

/** Journal readers validate graph authority as well as the common immutable snapshot edge. */
internal fun StudioGraphContinuationProof.requireSource(
    records: Map<String, StudioHelperReceipt>,
    previous: StudioHelperReceipt,
    next: StudioHelperReceipt,
) {
    check(source == previous.request && child == next.request && child == delivery.execution) {
        "Graph continuation changed its native edge"
    }
    check(delivery.fingerprint != null && source != child) { "Graph continuation has no reserved delivery" }
    val policy = previous.policy as? StudioAttemptPolicy.GraphWork
    check(policy?.recoveryRoot == root) { "Graph continuation changed its work root" }
    val original = checkNotNull(records[root.value]) { "Graph work root is missing" }
    check(original.policy == policy && original.fingerprint == rootFingerprint) { "Graph assignment changed" }
    val snapshot = checkNotNull(previous.executionSnapshot) { "Graph source snapshot is missing" }
    check(snapshot == original.executionSnapshot && previous.causes == snapshot.causes) {
        "Graph continuation changed its original execution authority"
    }
    check(snapshot.target == route.target && (previous.session ?: previous.preparedSession) == session) {
        "Graph source belongs to another native route"
    }
    when (evidence) {
        StudioGraphContinuationEvidence.Cancelled -> check(
            previous.phase in setOf(
                StudioHelperPhase.Submitting,
                StudioHelperPhase.Accepted,
                StudioHelperPhase.Terminal,
            ) &&
                (
                    previous.terminal == null || previous.terminal.outcome in setOf(
                        StudioHelperTerminalOutcome.Unknown,
                        StudioHelperTerminalOutcome.Cancelled,
                    )
                ),
        ) { "Exact cancellation proof contradicts the saved native result" }

        StudioGraphContinuationEvidence.FreshIdle -> check(
            previous.phase in setOf(StudioHelperPhase.Submitting, StudioHelperPhase.Accepted) ||
                previous.terminal?.outcome in setOf(
                    StudioHelperTerminalOutcome.Unknown,
                    StudioHelperTerminalOutcome.Cancelled,
                ),
        ) { "Fresh Idle cannot replace a confirmed result or an unprepared request" }
    }
}

/** Only a fully validated successor may supersede an unresolved historical native receipt. */
internal fun Map<String, StudioHelperReceipt>.unresolvedHeads(): List<StudioHelperReceipt> {
    val heads = mutableMapOf<RequestId, StudioHelperReceipt>()
    values.forEach { receipt ->
        val head = continuationChain(this, receipt.request).last()
        if (head.isUnresolved) heads[head.request] = head
    }
    return heads.values.toList()
}
