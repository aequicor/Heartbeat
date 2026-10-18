package io.aequicor.heartbeat.feature.harness.api.decision

import io.aequicor.heartbeat.feature.harness.api.process.ExecutorHostId
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Exact coordinator operation shown by the native host; author code cannot approve this payload. */
@Serializable
public enum class ProcessOwnershipAction {
    /** First conditional enrollment on the dedicated coordinator branch. */
    @SerialName("enroll")
    Enroll,

    /** Close admissions and name the only recipient allowed to claim the export. */
    @SerialName("prepare")
    Prepare,

    /** Rotate ownership only after a sealed source export. */
    @SerialName("claim")
    Claim,

    /** Rotate the source epoch; native drainage/reconciliation remains required. */
    @SerialName("cancel")
    Cancel,
}

/** Fixed branch and full expected snapshot make source, recipient and reviewed logic visible before approval. */
@Serializable
public data class ProcessOwnershipSnapshot(
    val action: ProcessOwnershipAction,
    val identity: GitHubTaskIdentity,
    val definitionDigest: String,
    val expected: ProcessOwnership?,
    val target: ExecutorHostId,
    val checkpointDigest: String? = null,
    val coordinatorBranch: String = "heartbeat-processes",
) {
    init {
        require(coordinatorBranch == "heartbeat-processes")
        require(Regex("[0-9a-f]{64}").matches(definitionDigest))
        require(checkpointDigest == null || Regex("[0-9a-f]{64}").matches(checkpointDigest))
    }
    override fun toString(): String = "ProcessOwnershipSnapshot(action=$action, ***)"
}
