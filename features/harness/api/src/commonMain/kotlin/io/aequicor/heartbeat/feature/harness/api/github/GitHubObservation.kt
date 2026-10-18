package io.aequicor.heartbeat.feature.harness.api.github

import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import kotlinx.serialization.Serializable

/**
 * Monotonic projection of a remote object. Equal revisions are duplicate or ambiguous deliveries and are ignored;
 * older deliveries cannot roll back a newer head or issue body. Input changes advance [inputRevision], and a new
 * PR head clears [verifiedHead]. A deletion requires explicit evidence; absence from a page does not delete state.
 */
@Serializable
public data class GitHubObservation(
    val entity: GitHubEntity,
    val inputRevision: Long = 0,
    val verifiedHead: SubjectSha? = null,
) {
    init {
        require(inputRevision >= 0) { "Invalid observation revision" }
        require(verifiedHead == null || (entity as? GitHubEntity.Pull)?.head == verifiedHead) { "Stale verification" }
    }

    /** Apply only newer evidence for the same object kind and identity. */
    public fun accept(next: GitHubEntity): GitHubObservation {
        require(next.id == entity.id && next::class == entity::class) { "Different GitHub object" }
        if (next.updatedAt <= entity.updatedAt) return this
        val hasNewInput = when {
            entity is GitHubEntity.Issue && next is GitHubEntity.Issue ->
                entity.title != next.title || entity.body != next.body

            entity is GitHubEntity.Pull && next is GitHubEntity.Pull -> entity.head != next.head

            else -> false
        }
        return copy(
            entity = next,
            inputRevision = if (hasNewInput) inputRevision + 1 else inputRevision,
            verifiedHead = verifiedHead.takeUnless { hasNewInput },
        )
    }

    override fun toString(): String = "GitHubObservation(***)"
}
