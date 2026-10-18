package io.aequicor.heartbeat.feature.harness.impl.data.ownership.github

import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.process.PortableEvidence
import io.aequicor.heartbeat.feature.harness.api.process.PortableGitHubKind
import io.aequicor.heartbeat.feature.harness.api.process.PortableGitHubTombstone
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubOperation
import io.aequicor.heartbeat.feature.harness.impl.domain.github.marked
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun GitHubOperation.tombstone(node: GitHubNodeId, evidence: PortableEvidence?): PortableGitHubTombstone =
    PortableGitHubTombstone(key, inputRevision, portablePayloadDigest(node), marker, evidence, refusal)

internal fun PortableEvidence.sameProof(other: PortableEvidence): Boolean = repositoryNode == other.repositoryNode &&
    kind == other.kind && number == other.number && digest == other.digest

internal fun GitHubOperation.locator(result: ResultRef): Pair<PortableGitHubKind, Long>? {
    val kind = when (result.kind) {
        "github_issue" -> PortableGitHubKind.Issue
        "github_pull", "github_merge_commit" -> PortableGitHubKind.Pull
        "github_comment" -> PortableGitHubKind.IssueComment
        "github_run" -> PortableGitHubKind.Run
        else -> return null
    }
    val number = if (result.kind == "github_merge_commit") {
        (change as? GitHubChange.Merge)?.number
    } else {
        result.id.toLongOrNull()
    }
    return number?.takeIf { it > 0 }?.let { kind to it }
}

internal fun GitHubOperation.matchesObservation(result: ResultRef, loaded: LoadedPortableEvidence): Boolean {
    val body = loaded.observation
    return when (val change = change) {
        is GitHubChange.CreateIssue ->
            body.text("title") == change.title && body.text("body") == change.body.marked(marker)

        is GitHubChange.Comment -> body.text("body") == change.body.marked(marker)

        is GitHubChange.CreatePull -> body.matchesPull(change, marker)

        is GitHubChange.Merge -> body.matchesMerge(change, result)

        is GitHubChange.Dispatch -> body.matchesRun(change, marker)

        is GitHubChange.AddSubIssue, is GitHubChange.AddProjectItem, is GitHubChange.CreateRelease -> false
    }
}

private fun JsonObject.matchesPull(change: GitHubChange.CreatePull, marker: String): Boolean =
    text("body") == change.body.marked(marker) && obj("head")?.text("sha") == change.head.value &&
        obj("head")?.text("ref") == change.branch && obj("base")?.text("ref") == change.base

private fun JsonObject.matchesMerge(change: GitHubChange.Merge, result: ResultRef): Boolean =
    get("merged")?.jsonPrimitive?.booleanOrNull == true && obj("head")?.text("sha") == change.head.value &&
        text("merge_commit_sha") == result.id

private fun JsonObject.matchesRun(change: GitHubChange.Dispatch, marker: String): Boolean =
    change.correlationInput != null && long("workflow_id") == change.workflow &&
        text("head_sha") == change.head.value &&
        text("event") == "workflow_dispatch" && text("display_title") == marker
