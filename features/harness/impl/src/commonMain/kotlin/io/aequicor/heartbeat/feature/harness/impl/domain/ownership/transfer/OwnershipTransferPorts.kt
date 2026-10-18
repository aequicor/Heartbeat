package io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorHostId
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.ProcessDefinition
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipChange
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipUnavailable
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess

/** Local native context; workspace and harness ids never enter the portable manifest or coordinator register. */
internal data class OwnershipTransferBinding(
    val identity: GitHubTaskIdentity,
    val definition: ProcessDefinition,
    val definitionDigest: String,
    val local: OwnershipLocalProcess,
    val workspace: String,
    val key: OwnershipKey,
) {
    init {
        require(Regex("[0-9a-f]{64}").matches(definitionDigest))
        require(incarnation.isNotBlank() && workspace.isNotBlank())
    }

    val harness: HarnessId get() = local.harness
    val process: ProcessId get() = local.process
    val incarnation: String get() = local.incarnation

    fun matches(manifest: PortableProcessCheckpoint): Boolean = manifest.identity.canonical == identity.canonical &&
        manifest.definitionDigest == definitionDigest && manifest.process == process &&
        manifest.incarnation == incarnation

    override fun toString(): String = "OwnershipTransferBinding(***)"
}

/** Content addressing belongs to the data adapter; host effects independently verify every supplied binding key. */
internal interface OwnershipTransferDigests {
    fun key(identity: GitHubTaskIdentity): OwnershipKey
    fun checkpoint(manifest: PortableProcessCheckpoint): String
}

/** Exact source or recipient phase snapshot. Local adapters must never apply it to work from another epoch. */
internal data class OwnershipTransferContext(val binding: OwnershipTransferBinding, val snapshot: ProcessOwnership) {
    val transfer: String get() = checkNotNull(snapshot.transfer).id

    override fun toString(): String = "OwnershipTransferContext(phase=${snapshot.phase}, ***)"
}

internal enum class OwnershipTransferAction {
    Begin,
    Claim,
    Cancel,
}

/** Native user decision binds the complete phase snapshot, target and reviewed logic, not a script callback. */
internal data class OwnershipTransferConsentRequest(
    val action: OwnershipTransferAction,
    val context: OwnershipTransferContext,
    val target: ExecutorHostId,
    val checkpoint: PortableProcessCheckpoint? = null,
) {
    override fun toString(): String = "OwnershipTransferConsentRequest(action=$action, ***)"
}

/** No production default: only a native human decision surface may implement this port. */
internal fun interface OwnershipTransferConsent {
    suspend fun confirm(request: OwnershipTransferConsentRequest): Boolean
}

/** Positive host receipts only; unavailable and unknown status are never interpreted as completion. */
internal sealed interface OwnershipLocalResult<out T> {
    data class Confirmed<T>(val receipt: T) : OwnershipLocalResult<T>
    data object Pending : OwnershipLocalResult<Nothing>
    data class Unavailable(val reason: OwnershipUnavailable) : OwnershipLocalResult<Nothing>
}

/** Includes a fresh post-drain coordinator snapshot; settled permit CASes may have advanced its revision. */
internal data class OwnershipDrainReceipt(
    val snapshot: ProcessOwnership,
    val checkpoint: PortableProcessCheckpoint,
    val executors: Int,
    val calls: Int,
) {
    init {
        require(executors >= 0 && calls >= 0)
    }
}

/** Fresh external reconciliation receipt pinned to the exact immutable artifact and current epoch. */
internal data class OwnershipReconciliationReceipt(
    val snapshot: ProcessOwnership,
    val checkpoint: OwnershipCheckpoint?,
    val unresolved: Int,
) {
    init {
        require(unresolved >= 0)
    }
}

/**
 * Private host adapter; no author code receives it. Every operation is bounded, restart-idempotent and journals
 * positive native receipts by transfer/epoch before returning. A stale context must not stop or import newer work.
 * A lost native submission is reconciled, never redispatched by retry. Native stop proof includes owned helpers,
 * organisms and external calls; terminal workflow status and coroutine cancellation are not such proof.
 */
internal interface OwnershipTransferLocal {
    /** Independently verifies node identities, workspace and approved source digest; target may have no record yet. */
    suspend fun verifyBinding(binding: OwnershipTransferBinding): Boolean

    /** Durably revokes local admissions for this epoch; returns the exact supplied phase snapshot on success. */
    suspend fun closeAdmissions(context: OwnershipTransferContext): OwnershipLocalResult<ProcessOwnership>

    /** Drains all admitted work and builds an allowlisted sealed manifest from independent protected journals. */
    suspend fun drainAndSeal(context: OwnershipTransferContext): OwnershipLocalResult<OwnershipDrainReceipt>

    /**
     * Installs record, evidence catalog and permanent GitHub tombstones idempotently, then freshly reads external
     * facts. Uses independently computed binding.definitionDigest and never copies grants or native approvals.
     * No executor is started during this call. Missing/unresolved remote facts keep the receipt pending.
     */
    suspend fun reconcileRecipient(
        context: OwnershipTransferContext,
        checkpoint: PortableProcessCheckpoint,
    ): OwnershipLocalResult<OwnershipReconciliationReceipt>

    /** Cancellation reconciles the source's existing state and all older permits; it never imports over it. */
    suspend fun reconcileSource(context: OwnershipTransferContext): OwnershipLocalResult<OwnershipReconciliationReceipt>
}

/** Content-addressed immutable artifacts; null means unavailable and never authorizes import or export. */
internal interface OwnershipTransferArtifacts {
    suspend fun store(checkpoint: PortableProcessCheckpoint): OwnershipCheckpoint?
    suspend fun load(pointer: OwnershipCheckpoint): PortableProcessCheckpoint?
}

internal enum class OwnershipTransferRefusal {
    Binding,
    Owner,
    Phase,
    Consent,
    Checkpoint,
    Receipt,
}

/** One call changes at most one coordinator phase; every response must be observed before requesting another. */
internal sealed interface OwnershipTransferResult {
    data class Coordinated(val change: OwnershipChange) : OwnershipTransferResult
    data class Waiting(val snapshot: ProcessOwnership) : OwnershipTransferResult
    data class Refused(val reason: OwnershipTransferRefusal) : OwnershipTransferResult
    data class Unavailable(val reason: OwnershipUnavailable) : OwnershipTransferResult
}
