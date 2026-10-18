package io.aequicor.heartbeat.feature.worktreemode.impl.data

/** Full immutable Git references for pinned stages, always resolved inside their selected repository. */
internal class PinnedSandboxProofs(
    private val read: suspend (String, List<String>) -> String?,
    private val write: suspend (String, List<String>) -> String,
) {
    suspend fun pin(plan: WorktreeProvision, commit: String): WorktreeProvision {
        require(commit.length in setOf(SHA1_LENGTH, SHA256_LENGTH) && commit.all { it in '0'..'9' || it in 'a'..'f' }) {
            "FullCommitRequired"
        }
        val resolved = write(plan.sourceDirectory, listOf("rev-parse", "--verify", "$commit^{commit}"))
        check(resolved == commit) { "PinnedBaseUnavailable" }
        return plan.copy(baseCommit = resolved)
    }

    suspend fun existing(record: WorktreeRecord, operation: String): String? {
        val base = record.task.pinnedBaseCommit ?: return null
        checkNotNull(read(record.directory, listOf("merge-base", "--is-ancestor", base, "HEAD"))) {
            "PinnedBaseMismatch"
        }
        val expected = record.resultProofs[operation] ?: record.task.seal?.takeIf { it.operation == operation }?.commit
        val prior = read(record.sourceDirectory, listOf("rev-parse", "--verify", "$RESULT_REFS$operation^{commit}"))
        check(expected == null || prior == expected) { "ResultProofUnavailable" }
        if (prior != null) {
            checkNotNull(read(record.sourceDirectory, listOf("merge-base", "--is-ancestor", base, prior))) {
                "ResultProofMismatch"
            }
        }
        return prior
    }

    suspend fun publish(record: WorktreeRecord, operation: String, commit: String) {
        val expected = if (record.task.pinnedBaseCommit == null) emptyList() else listOf("0".repeat(commit.length))
        // Compare-and-set refuses replacement by another owner between inspection and publication.
        write(record.sourceDirectory, listOf("update-ref", "$RESULT_REFS$operation", commit) + expected)
    }
}

private const val SHA1_LENGTH = 40
private const val SHA256_LENGTH = 64
private const val RESULT_REFS = "refs/heartbeat/results/"
