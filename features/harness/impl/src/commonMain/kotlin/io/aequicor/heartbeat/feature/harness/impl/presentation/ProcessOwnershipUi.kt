package io.aequicor.heartbeat.feature.harness.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessOwnershipSnapshot

/** Human-readable native decision projection. It contains no approval, executable callback or portable grant. */
@Immutable
internal data class ProcessOwnershipUi(
    val action: OwnershipActionUi,
    val source: String?,
    val recipient: String,
    val epoch: Long?,
    val checkpoint: String?,
    val definition: String,
    val branch: String,
    val repository: String,
    val taskKind: OwnershipTaskKindUi,
    val taskNumber: Long,
) {
    override fun toString(): String = "ProcessOwnershipUi(action=$action, ***)"
}

internal enum class OwnershipActionUi { Enroll, Prepare, Claim, Cancel }

internal enum class OwnershipTaskKindUi { Issue, Pull }

internal fun ProcessOwnershipSnapshot.toUi(): ProcessOwnershipUi = ProcessOwnershipUi(
    action = OwnershipActionUi.valueOf(action.name),
    source = (expected?.transfer?.source ?: expected?.fence)?.host?.value,
    recipient = target.value,
    epoch = expected?.fence?.epoch,
    checkpoint = checkpointDigest ?: expected?.transfer?.checkpoint?.digest,
    definition = definitionDigest,
    branch = coordinatorBranch,
    repository = "${identity.repository.owner}/${identity.repository.name}",
    taskKind = OwnershipTaskKindUi.valueOf(identity.kind.name),
    taskNumber = identity.number,
)
