package io.aequicor.heartbeat.feature.harness.api.github

import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import kotlinx.serialization.Serializable

/**
 * Monotonic projection of a remote object. Older deliveries cannot roll back newer evidence. Conflicting objects
 * at the same server timestamp invalidate the projection until an authoritative [refresh]; GitHub timestamps
 * do not establish an order between such objects. Persist ambiguity with the projection and consume [current]
 * for stage decisions. [entity] retains historical evidence while ambiguous, never a usable current snapshot.
 * A deletion requires explicit evidence; absence from a page does not delete state.
 */
@Serializable
public data class GitHubObservation(
    val entity: GitHubEntity,
    val inputRevision: Long = 0,
    val verifiedHead: SubjectSha? = null,
    val isAmbiguous: Boolean = false,
) {
    init {
        require(inputRevision >= 0) { "Invalid observation revision" }
        require(verifiedHead == null || (!isAmbiguous && (entity as? GitHubEntity.Pull)?.head == verifiedHead)) {
            "Stale verification"
        }
    }

    /** Usable current evidence, absent until a conflicting server revision has been refreshed. */
    public val current: GitHubEntity? get() = entity.takeUnless { isAmbiguous }

    /**
     * Accept ordinary deliveries for the same object. An exact duplicate is idempotent. The first conflicting
     * equal timestamp advances [inputRevision] once and clears verification; further deliveries cannot resolve
     * ambiguity, even if they repeat historical evidence. Use [refresh] after a fresh current-object read.
     */
    public fun accept(next: GitHubEntity): GitHubObservation {
        requireSameObject(next)
        return when {
            isAmbiguous || next.updatedAt < entity.updatedAt || next == entity -> this

            next.updatedAt == entity.updatedAt ->
                copy(inputRevision = inputRevision + 1, verifiedHead = null, isAmbiguous = true)

            else -> updated(next)
        }
    }

    /**
     * Resolve from a fresh current-object read awaited after all earlier reads for this object have completed.
     * Never use this for webhook deliveries, cached pages or concurrently completing requests. Equal timestamps
     * are accepted because the caller establishes read order; older server timestamps are still ignored. Resolving
     * ambiguity does not advance [inputRevision] again and cannot restore the invalidated verification.
     */
    public fun refresh(next: GitHubEntity): GitHubObservation {
        requireSameObject(next)
        if (next.updatedAt < entity.updatedAt) return this
        return updated(next)
    }

    private fun requireSameObject(next: GitHubEntity) {
        require(next.id == entity.id && next::class == entity::class) { "Different GitHub object" }
    }

    private fun updated(next: GitHubEntity): GitHubObservation {
        val hasNewInput = when {
            entity is GitHubEntity.Issue && next is GitHubEntity.Issue ->
                entity.title != next.title || entity.body != next.body

            entity is GitHubEntity.Pull && next is GitHubEntity.Pull -> entity.head != next.head

            else -> false
        }
        return copy(
            entity = next,
            inputRevision = if (hasNewInput && !isAmbiguous) inputRevision + 1 else inputRevision,
            verifiedHead = verifiedHead.takeUnless { hasNewInput || isAmbiguous },
            isAmbiguous = false,
        )
    }

    override fun toString(): String = "GitHubObservation(***)"
}
