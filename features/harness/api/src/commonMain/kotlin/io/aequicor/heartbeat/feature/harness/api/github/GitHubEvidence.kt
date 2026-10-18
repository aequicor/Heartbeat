package io.aequicor.heartbeat.feature.harness.api.github

import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import kotlinx.serialization.Serializable

/** Exact evidence checks; successful transport or an agent's answer is never sufficient. */
public object GitHubEvidence {
    /**
     * Accept only a completed successful dispatch of the requested workflow/head with all expected unexpired
     * artifacts. Fetch artifact pages from the exact run id and pass false for [areArtifactsComplete] until the
     * final page. Names alone cannot substitute for run ownership.
     */
    public fun run(
        run: GitHubEntity.Run,
        expectation: GitHubRunExpectation,
        artifacts: List<GitHubEntity.Artifact>,
        areArtifactsComplete: Boolean,
    ): Boolean =
        run.workflow == expectation.workflow && run.head == expectation.head && run.event == "workflow_dispatch" &&
            run.status == "completed" && run.conclusion == "success" && areArtifactsComplete &&
            artifacts.all { it.run == run.id } &&
            artifacts.filterNot { it.isExpired }.map { it.name }.toSet().containsAll(expectation.artifacts)

    /**
     * A published release must name the expected tag, whose separately peeled commit equals [head], and include
     * every expected asset. A draft and a branch-valued target_commitish do not establish a published artifact.
     */
    public fun release(
        release: GitHubEntity.Release,
        tag: String,
        tagCommit: SubjectSha?,
        head: SubjectSha,
        expectedAssets: Set<String>,
    ): Boolean = !release.isDraft && release.tag == tag && tagCommit == head &&
        release.assets.toSet().containsAll(expectedAssets)
}

/** Immutable identity and required outputs of an Actions verification request. */
@Serializable
public data class GitHubRunExpectation(val workflow: Long, val head: SubjectSha, val artifacts: Set<String>) {
    override fun toString(): String = "GitHubRunExpectation(***)"
}
