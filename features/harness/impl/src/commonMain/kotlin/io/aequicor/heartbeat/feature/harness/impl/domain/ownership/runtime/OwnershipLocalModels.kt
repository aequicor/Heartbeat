package io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipFence
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermit
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import kotlinx.serialization.Serializable

/** Exact local lifetime, never a source of the shared task identity. */
@Serializable
internal data class OwnershipLocalProcess(val harness: HarnessId, val process: ProcessId, val incarnation: String) {
    override fun toString(): String = "OwnershipLocalProcess(***)"
}

/** Host-approved enrollment; a script-owned ProcessRecord cannot create or remove this binding. */
@Serializable
internal data class OwnershipLocalBinding(
    val local: OwnershipLocalProcess,
    val identity: GitHubTaskIdentity,
    val fence: OwnershipFence,
    /** Native-approved working copy for phase progress; null only in pre-runtime/test journals. */
    val workspace: io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef? = null,
    val definitionDigest: String? = null,
    /** Protected proof that this profile held the same lifetime before the current recipient Claim. */
    val previousOwner: OwnershipFence? = null,
    /** Published import for this exact fence. Same-epoch replay must never overwrite newer local progress. */
    val imported: OwnershipCheckpoint? = null,
) {
    init {
        require(
            previousOwner == null || (
                previousOwner.key == fence.key &&
                    previousOwner.host == fence.host && previousOwner.epoch < fence.epoch
            ),
        )
    }
    override fun toString(): String = "OwnershipLocalBinding(***)"
}

/** Fixed proposal persisted BEFORE coordinator admission; its sequence can never be replaced on retry. */
@Serializable
internal data class OwnershipNativeIntent(val local: OwnershipLocalProcess, val permit: OwnershipPermit) {
    override fun toString(): String = "OwnershipNativeIntent(***)"
}

/** Protected run identity reconstructs its exact intent without consulting script-owned markers. */
internal fun io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun.ownershipIntent(): OwnershipNativeIntent? {
    val binding = process ?: return null
    val permit = binding.ownership ?: return null
    return OwnershipNativeIntent(OwnershipLocalProcess(harness, binding.key.process, binding.key.incarnation), permit)
}
