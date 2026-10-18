package io.aequicor.heartbeat.feature.harness.impl.data.ownership.github

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.process.AttemptOutcome
import io.aequicor.heartbeat.feature.harness.api.process.PORTABLE_GITHUB_OPERATIONS
import io.aequicor.heartbeat.feature.harness.api.process.PortableEvidence
import io.aequicor.heartbeat.feature.harness.api.process.PortableGitHubTombstone
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessReceipts
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.api.process.WritePhase
import io.aequicor.heartbeat.feature.harness.api.process.portableRefusal
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubApi
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubOperation
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubReconciliation
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRegistry
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRegistrySnapshot
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubReservation
import io.aequicor.heartbeat.feature.harness.impl.domain.github.ImportedGitHubCheck
import io.aequicor.heartbeat.feature.harness.impl.domain.github.PortableGitHubAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.github.PortableGitHubFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess
import kotlinx.coroutines.CancellationException

internal sealed interface PortableGitHubExport {
    data class Ready(val receipts: PortableProcessReceipts) : PortableGitHubExport
    data class Unavailable(val reason: PortableGitHubFailure) : PortableGitHubExport
}

/** Positive fresh external effect, including a formerly unknown operation recovered without resubmission. */
internal data class NativeGitHubReceipt(val operation: GitHubOperation, val result: ResultRef?) {
    init {
        require(result != null || (operation.hasSendStarted == false && operation.refusal != null))
    }
    override fun toString(): String = "NativeGitHubReceipt(***)"
}

/**
 * Read-only portable bridge over native registry and protected imports. Fresh sends must first call checkImported;
 * a transported tombstone can never be recreated as a native reservation. Native registry results are independently
 * found on GitHub before sealing. Reimported evidence can be freshly checked by its numeric locator without any
 * original request payload. No approval, workspace locator or credential enters a portable digest or manifest.
 */
internal class PortableGitHubAdapter(
    api: GitHubApi,
    private val registry: GitHubRegistry,
    private val imported: ProtectedImportedGitHub,
) : PortableGitHubAccess {
    private val reader = PortableGitHubEvidenceReader(api)
    private val reconciliation = GitHubReconciliation(api)
    private val log = Log.tag("PortableGitHub")

    suspend fun exportReceipts(
        local: OwnershipLocalProcess,
        record: ProcessRecord,
        definitionDigest: String,
        isNativeStopped: Boolean,
    ): PortableGitHubExport = guarded(PortableGitHubExport.Unavailable(PortableGitHubFailure.Storage)) {
        when {
            record.id != local.process || record.incarnation != local.incarnation ->
                unavailable(PortableGitHubFailure.Identity)

            !isNativeStopped -> unavailable(PortableGitHubFailure.NativeStopUnconfirmed)

            record.unresolvedWrites().isNotEmpty() -> unavailable(PortableGitHubFailure.Unresolved)

            else -> exportVerified(local, record, definitionDigest)
        }
    }

    private suspend fun exportVerified(
        local: OwnershipLocalProcess,
        record: ProcessRecord,
        definitionDigest: String,
    ): PortableGitHubExport {
        val snapshot = registry.snapshot(local.process, local.incarnation)
        val prior = imported.snapshot(local)
        return if (!verify(prior)) {
            unavailable(PortableGitHubFailure.Unresolved)
        } else {
            exportNative(local, record, definitionDigest, snapshot, prior)
        }
    }

    private suspend fun exportNative(
        local: OwnershipLocalProcess,
        record: ProcessRecord,
        definitionDigest: String,
        snapshot: GitHubRegistrySnapshot,
        prior: ImportedGitHubSnapshot,
    ): PortableGitHubExport {
        val proofs = PortableGitHubProofs(prior)
        var failure: PortableGitHubFailure? = null
        for (operation in snapshot.operations) {
            if (failure == null) failure = addOperation(proofs, operation)
        }
        return when {
            failure != null -> unavailable(failure)

            proofs.tombstones.size > PORTABLE_GITHUB_OPERATIONS -> unavailable(PortableGitHubFailure.Quota)

            record.requiredReferences(proofs.tombstones).any { it !in proofs.evidence } ->
                unavailable(PortableGitHubFailure.UnsupportedReference)

            !registry.isCurrent(snapshot) || imported.snapshot(local) != prior ->
                unavailable(PortableGitHubFailure.RegistryChanged)

            else -> PortableGitHubExport.Ready(
                PortableProcessReceipts(
                    definitionDigest,
                    true,
                    proofs.evidence,
                    proofs.tombstones.size,
                    proofs.tombstones,
                ),
            )
        }
    }

    private suspend fun addOperation(proofs: PortableGitHubProofs, operation: GitHubOperation): PortableGitHubFailure? {
        val result = operation.result
        return if (result != null && operation.locator(result) == null) {
            PortableGitHubFailure.UnsupportedReference
        } else {
            val sealed = seal(operation)
            when {
                sealed == null -> PortableGitHubFailure.Unresolved
                !proofs.add(operation, sealed) -> PortableGitHubFailure.Conflict
                else -> null
            }
        }
    }

    /** Called before target Activate, and before reexporting any earlier imported evidence. */
    suspend fun reconcileImported(local: OwnershipLocalProcess): Boolean = guarded(false) {
        val snapshot = imported.snapshot(local)
        snapshot.identity != null && verify(snapshot) && imported.snapshot(local) == snapshot
    }

    /** Source cancellation retains its local journals and does not require portability of opaque stage results. */
    suspend fun reconcileNative(local: OwnershipLocalProcess): Boolean = guarded(false) {
        val native = registry.snapshot(local.process, local.incarnation)
        val prior = imported.snapshot(local)
        var isVerified = prior.identity == null || verify(prior)
        for (operation in native.operations) {
            val result = operation.result
            val isRefused = result == null && operation.refusal != null
            val isConfirmed = result != null && operation.refusal == null && reconciliation.find(operation) == result
            if (!isRefused && !isConfirmed) isVerified = false
        }
        isVerified && registry.isCurrent(native) && imported.snapshot(local) == prior
    }

    /**
     * Caller holds the process writer barrier, joining every actual send. Finds positive remote effects for the
     * complete lifetime, including older retained keys; unknown operations may be settled only by that proof.
     * A stored pre-dispatch refusal needs no native release receipt. Missing/ambiguous evidence returns null,
     * never a not-applied guess. Storage uncertainty is reconciled by a later call without resending anything.
     */
    suspend fun reconcileAndCollectNative(local: OwnershipLocalProcess): List<NativeGitHubReceipt>? =
        guarded<List<NativeGitHubReceipt>?>(null) {
            val before = registry.snapshot(local.process, local.incarnation)
            val receipts = collectSnapshot(before)
            if (receipts != null && isCollectionCurrent(local, before, receipts)) receipts else null
        }

    private suspend fun collectSnapshot(before: GitHubRegistrySnapshot): List<NativeGitHubReceipt>? {
        val receipts = mutableListOf<NativeGitHubReceipt>()
        for (operation in before.operations) {
            if (operation.hasSendStarted == false && operation.result == null) {
                val closed = registry.closeUnsent(operation) ?: return null
                receipts += NativeGitHubReceipt(closed, null)
            } else if (operation.refusal == null) {
                val receipt = collect(operation) ?: return null
                receipts += receipt
            }
        }
        return receipts
    }

    private suspend fun isCollectionCurrent(
        local: OwnershipLocalProcess,
        before: GitHubRegistrySnapshot,
        receipts: List<NativeGitHubReceipt>,
    ): Boolean {
        val after = registry.snapshot(local.process, local.incarnation)
        val results = receipts.associateBy { it.operation.key }
        val expected = before.operations.map { original -> results[original.key]?.operation ?: original }
        val settlements = before.operations.count {
            val receipt = results[it.key]
            receipt != null && receipt.operation != it
        }.toLong()
        val isSameRevision = before.revision <= Long.MAX_VALUE - settlements &&
            after.revision == before.revision + settlements
        return isSameRevision && after.operations == expected
    }

    private suspend fun collect(operation: GitHubOperation): NativeGitHubReceipt? {
        val result = reconciliation.find(operation)
        val isValid = operation.refusal == null && result != null &&
            (operation.result == null || operation.result == result)
        return if (!isValid) {
            null
        } else {
            val confirmed = result
            if (operation.result == null) registry.settle(operation, result = confirmed)
            NativeGitHubReceipt(operation.copy(result = confirmed), confirmed)
        }
    }

    /** Read-only reconciliation by immutable imported key; the payload and original native decision are absent. */
    override suspend fun reconcileImportedKey(local: OwnershipLocalProcess, key: WriteKey): ImportedGitHubCheck =
        guarded(ImportedGitHubCheck.Unavailable(PortableGitHubFailure.Storage)) {
            val previous = imported.lookup(local, key)
            val refusal = previous?.refusal
            when {
                previous == null -> ImportedGitHubCheck.Absent

                refusal != null -> ImportedGitHubCheck.Refused(refusal, previous.marker)

                else -> {
                    val evidence = checkNotNull(previous.result)
                    if (reader.verify(evidence)) {
                        ImportedGitHubCheck.Confirmed(evidence.asResultRef(), previous.marker)
                    } else {
                        ImportedGitHubCheck.Unknown
                    }
                }
            }
        }

    private suspend fun verify(snapshot: ImportedGitHubSnapshot): Boolean {
        for (evidence in snapshot.evidence) {
            if (!reader.verify(evidence)) return false
        }
        return true
    }

    override suspend fun checkImported(local: OwnershipLocalProcess, candidate: GitHubOperation): ImportedGitHubCheck =
        guarded(ImportedGitHubCheck.Unavailable(PortableGitHubFailure.Storage)) {
            if (candidate.process != local.process || candidate.incarnation != local.incarnation) {
                ImportedGitHubCheck.Conflict
            } else {
                val snapshot = imported.snapshot(local)
                val previous = snapshot.tombstones.singleOrNull { it.key == candidate.key }
                if (previous == null) capacity(local, candidate, snapshot) else replay(candidate, previous)
            }
        }

    /** Fresh reservation only; caller already handled checkImported. Capacity is enforced by the native CAS. */
    override suspend fun reserveNative(local: OwnershipLocalProcess, candidate: GitHubOperation): GitHubReservation {
        require(candidate.process == local.process && candidate.incarnation == local.incarnation)
        val retained = imported.snapshot(local).tombstones.map { it.key }.toSet()
        return registry.reserve(candidate, retained)
    }

    private suspend fun capacity(
        local: OwnershipLocalProcess,
        candidate: GitHubOperation,
        imported: ImportedGitHubSnapshot,
    ): ImportedGitHubCheck {
        val native = registry.snapshot(local.process, local.incarnation)
        val keys = (native.operations.map { it.key } + imported.tombstones.map { it.key }).toSet()
        return if (candidate.key !in keys && keys.size >= PORTABLE_GITHUB_OPERATIONS) {
            ImportedGitHubCheck.Quota
        } else {
            ImportedGitHubCheck.Absent
        }
    }

    private suspend fun replay(candidate: GitHubOperation, previous: PortableGitHubTombstone): ImportedGitHubCheck {
        val node = reader.repositoryNode(candidate.repository)
        val refusal = previous.refusal
        return when {
            node == null -> ImportedGitHubCheck.Unknown

            previous.inputRevision != candidate.inputRevision ||
                previous.payloadDigest != candidate.portablePayloadDigest(node) -> ImportedGitHubCheck.Conflict

            refusal != null -> ImportedGitHubCheck.Refused(refusal, previous.marker)

            else -> replayConfirmed(candidate, previous)
        }
    }

    private suspend fun replayConfirmed(
        candidate: GitHubOperation,
        previous: PortableGitHubTombstone,
    ): ImportedGitHubCheck {
        val operation = candidate.copy(
            marker = previous.marker,
            result = null,
            refusal = null,
            decisionId = null,
            decisionBinding = null,
        )
        val fresh = reconciliation.find(operation)
        val evidence = fresh?.let { sealResult(operation, it) }
        return if (evidence != null && evidence.sameProof(checkNotNull(previous.result))) {
            ImportedGitHubCheck.Confirmed(fresh, previous.marker)
        } else {
            ImportedGitHubCheck.Unknown
        }
    }

    private suspend fun seal(operation: GitHubOperation): PortableGitHubTombstone? =
        reader.repositoryNode(operation.repository)?.let { node ->
            seal(operation, node)
        }

    private suspend fun seal(operation: GitHubOperation, node: GitHubNodeId): PortableGitHubTombstone? {
        val result = operation.result
        val refusal = operation.refusal
        return when {
            (result == null) == (refusal == null) -> null

            result == null -> operation.tombstone(node, null)

            reconciliation.find(operation) != result -> null

            else -> sealResult(operation, result)?.takeIf { it.repositoryNode == node }?.let {
                operation.tombstone(node, it)
            }
        }
    }

    private suspend fun sealResult(operation: GitHubOperation, result: ResultRef): PortableEvidence? {
        val locator = operation.locator(result) ?: return null
        val loaded = reader.read(operation.repository, locator.first, locator.second)
        return if (loaded != null && operation.matchesObservation(result, loaded)) loaded.evidence else null
    }

    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Portable GitHub evidence is unavailable")) {
            "portable GitHub evidence rejected (${error::class.simpleName.orEmpty()})"
        }
        fallback
    }
}

private fun unavailable(reason: PortableGitHubFailure): PortableGitHubExport = PortableGitHubExport.Unavailable(reason)

private fun ProcessRecord.requiredReferences(tombstones: List<PortableGitHubTombstone>): List<ResultRef> =
    history.mapNotNull { (it.outcome as? AttemptOutcome.Succeeded)?.result } +
        writes.flatMap {
            val target = if (it.portableRefusal(tombstones) == null) it.target else null
            listOfNotNull(target, (it.phase as? WritePhase.Confirmed)?.result)
        }
