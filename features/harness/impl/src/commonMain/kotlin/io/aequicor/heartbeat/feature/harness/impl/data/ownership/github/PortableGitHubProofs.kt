package io.aequicor.heartbeat.feature.harness.impl.data.ownership.github

import io.aequicor.heartbeat.feature.harness.api.process.PortableEvidence
import io.aequicor.heartbeat.feature.harness.api.process.PortableGitHubTombstone
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubOperation

/** One export's transient verified index. Conflicting identities are never resolved by taking the last writer. */
internal class PortableGitHubProofs(prior: ImportedGitHubSnapshot) {
    private val references = prior.evidence.associateBy { it.asResultRef() }.toMutableMap()
    private val seals = prior.evidence.associateBy { it.digest }.toMutableMap()
    private val operations = prior.tombstones.associateBy { it.key }.toMutableMap()

    val evidence: Map<ResultRef, PortableEvidence> get() = references.toMap()
    val tombstones: List<PortableGitHubTombstone> get() = operations.values.toList()

    fun add(operation: GitHubOperation, tombstone: PortableGitHubTombstone): Boolean {
        val result = tombstone.result
        val existingSeal = result?.let { seals[it.digest] }
        return if (result != null && existingSeal != null && !result.sameProof(existingSeal)) {
            false
        } else {
            addNormalized(operation, tombstone.copy(result = existingSeal ?: result))
        }
    }

    private fun addNormalized(operation: GitHubOperation, normalized: PortableGitHubTombstone): Boolean {
        val existing = operations[operation.key]
        val proof = normalized.result
        return when {
            existing != null && existing != normalized -> false

            proof == null -> {
                operations[operation.key] = normalized
                true
            }

            else -> {
                val isAccepted = putReference(checkNotNull(operation.result), proof) &&
                    putReference(ResultRef("github_operation", operation.marker), proof)
                if (isAccepted) {
                    seals[proof.digest] = proof
                    operations[operation.key] = normalized
                }
                isAccepted
            }
        }
    }

    private fun putReference(reference: ResultRef, proof: PortableEvidence): Boolean {
        val previous = references[reference]
        return if (previous != null && !previous.sameProof(proof)) {
            false
        } else {
            references[reference] = previous ?: proof
            true
        }
    }
}
