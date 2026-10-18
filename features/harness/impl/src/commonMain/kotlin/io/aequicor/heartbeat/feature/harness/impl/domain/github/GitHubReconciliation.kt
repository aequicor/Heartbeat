package io.aequicor.heartbeat.feature.harness.impl.domain.github

import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubEntity
import io.aequicor.heartbeat.feature.harness.api.github.GitHubQuery
import io.aequicor.heartbeat.feature.harness.api.github.GitHubReadResult
import io.aequicor.heartbeat.feature.harness.api.github.GitHubSnapshot
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Bounded read-only reconciliation. Missing, incomplete or ambiguous evidence always remains Unknown. */
internal class GitHubReconciliation(private val api: GitHubApi) {
    suspend fun find(operation: GitHubOperation): ResultRef? {
        if (operation.change is GitHubChange.Merge) return merged(operation)
        val matches = mutableListOf<GitHubEntity>()
        var cursor: String? = null
        for (page in 1..GITHUB_MAX_PAGES) {
            val query = operation.query(page, cursor) ?: return null
            val snapshot = (api.read(operation.repository, query) as? GitHubReadResult.Read)?.snapshot ?: return null
            matches += snapshot.entries.filter { operation.matches(it) }
            if (matches.size > 1) return null
            if (snapshot.nextPage == null && snapshot.nextCursor == null) {
                val found = matches.singleOrNull() ?: return null
                if (operation.change is GitHubChange.CreateRelease && !sameTag(operation, operation.change)) return null
                return if (operation.change is GitHubChange.AddSubIssue) {
                    ResultRef("github_sub_issue", found.id)
                } else {
                    found.reference()
                }
            }
            cursor = snapshot.nextCursor
        }
        return null
    }

    suspend fun preflight(operation: GitHubOperation): Boolean = when (val change = operation.change) {
        is GitHubChange.CreatePull -> commit(operation, change.branch) == change.head

        is GitHubChange.Dispatch -> commit(operation, change.ref) == change.head

        is GitHubChange.CreateRelease -> sameTag(operation, change)

        is GitHubChange.Merge -> {
            val pull = snapshot(
                operation,
                GitHubQuery.Pull(change.number),
            )?.entries?.singleOrNull() as? GitHubEntity.Pull
            pull?.head == change.head && pull.state == "open"
        }

        else -> true
    }

    /** A successful HTTP response alone cannot prove dispatch's actual head or the release's peeled tag. */
    suspend fun response(operation: GitHubOperation, response: GitHubResponse): ResultRef? {
        if (response.status !in GitHubHttp.SUCCESS) return null
        val body = response.body as? JsonObject ?: return null
        return when (val change = operation.change) {
            is GitHubChange.Dispatch -> dispatchResponse(operation, change, body)

            is GitHubChange.CreatePull -> {
                if (body.obj(
                        "head",
                    ).text("sha") == change.head.value
                ) {
                    ResultRef("github_pull", body.text("number"))
                } else {
                    null
                }
            }

            is GitHubChange.AddProjectItem -> {
                if ("errors" in body) {
                    null
                } else {
                    ResultRef(
                        "github_project_item",
                        body.obj("data").obj("addProjectV2ItemById").obj("item").text("id"),
                    )
                }
            }

            is GitHubChange.Merge -> if (body["merged"]?.jsonPrimitive?.booleanOrNull == true) {
                ResultRef("github_merge_commit", body.text("sha"))
            } else {
                null
            }

            is GitHubChange.CreateRelease -> {
                if (body.optional("tag_name") == change.tag && sameTag(operation, change)) {
                    ResultRef("github_release", body.text("id"))
                } else {
                    null
                }
            }

            is GitHubChange.CreateIssue, is GitHubChange.Comment, is GitHubChange.AddSubIssue ->
                operation.change.simpleReference(body)
        }
    }

    private suspend fun dispatchResponse(
        operation: GitHubOperation,
        change: GitHubChange.Dispatch,
        body: JsonObject,
    ): ResultRef? {
        val id = body.optional("workflow_run_id")?.toLongOrNull() ?: return null
        val run = snapshot(operation, GitHubQuery.Run(id))?.entries?.singleOrNull() as? GitHubEntity.Run ?: return null
        return ResultRef("github_run", run.id).takeIf { run.isExpected(change) }
    }

    private suspend fun merged(operation: GitHubOperation): ResultRef? {
        val change = operation.change as GitHubChange.Merge
        val response = api.call(GitHubQuery.Pull(change.number).request(operation.repository))
        val obj = response.body as? JsonObject ?: return null
        return if (response.status == GitHubHttp.OK && obj.obj("head").text("sha") == change.head.value &&
            obj["merged"]?.jsonPrimitive?.booleanOrNull == true
        ) {
            obj.optional("merge_commit_sha")?.let { ResultRef("github_merge_commit", it) }
        } else {
            null
        }
    }

    private suspend fun sameTag(operation: GitHubOperation, change: GitHubChange.CreateRelease): Boolean =
        snapshot(operation, GitHubQuery.Tag(change.tag))?.commit == change.head

    private suspend fun commit(operation: GitHubOperation, ref: String) = snapshot(
        operation,
        GitHubQuery.Commit(ref),
    )?.commit

    private suspend fun snapshot(operation: GitHubOperation, query: GitHubQuery): GitHubSnapshot? =
        (api.read(operation.repository, query) as? GitHubReadResult.Read)?.snapshot
}

private fun GitHubOperation.query(page: Int, cursor: String?): GitHubQuery? = when (val change = change) {
    is GitHubChange.CreateIssue -> GitHubQuery.Issues(page)
    is GitHubChange.CreatePull -> GitHubQuery.Pulls(page)
    is GitHubChange.Comment -> GitHubQuery.Comments(change.number, page)
    is GitHubChange.AddSubIssue -> GitHubQuery.SubIssues(change.parent, page)
    is GitHubChange.AddProjectItem -> GitHubQuery.ProjectItems(change.project, cursor)
    is GitHubChange.Dispatch -> GitHubQuery.Runs(change.workflow, page)
    is GitHubChange.CreateRelease -> GitHubQuery.Releases(page)
    is GitHubChange.Merge -> null
}

private fun GitHubOperation.matches(entity: GitHubEntity): Boolean = when (val change = change) {
    is GitHubChange.CreateIssue -> (entity as? GitHubEntity.Issue)?.let {
        it.title == change.title && it.body == change.body.marked(marker)
    } == true

    is GitHubChange.CreatePull -> (entity as? GitHubEntity.Pull)?.matches(change, marker) == true

    is GitHubChange.Comment -> (entity as? GitHubEntity.Comment)?.body == change.body.marked(marker)

    is GitHubChange.AddSubIssue -> (entity as? GitHubEntity.Issue)?.id == change.child.toString()

    is GitHubChange.AddProjectItem -> (entity as? GitHubEntity.ProjectItem)?.content == change.content

    is GitHubChange.Dispatch -> (entity as? GitHubEntity.Run)?.matches(change, marker) == true

    is GitHubChange.CreateRelease -> (entity as? GitHubEntity.Release)?.let {
        it.tag == change.tag && it.body == change.body.marked(marker)
    } == true

    is GitHubChange.Merge -> false
}

private fun GitHubEntity.Pull.matches(change: GitHubChange.CreatePull, marker: String): Boolean =
    sameRoute(change) && head == change.head && body == change.body.marked(marker)

private fun GitHubEntity.Run.matches(change: GitHubChange.Dispatch, marker: String): Boolean =
    change.correlationInput != null && isExpected(change) && title == marker

private fun GitHubEntity.Run.isExpected(change: GitHubChange.Dispatch): Boolean =
    workflow == change.workflow && head == change.head && event == "workflow_dispatch"

private fun GitHubEntity.Pull.sameRoute(change: GitHubChange.CreatePull): Boolean =
    branch == change.branch && base == change.base

private fun GitHubEntity.reference(): ResultRef = when (this) {
    is GitHubEntity.Issue -> ResultRef("github_issue", number.toString())
    is GitHubEntity.Pull -> ResultRef("github_pull", number.toString())
    is GitHubEntity.Comment -> ResultRef("github_comment", id)
    is GitHubEntity.ProjectItem -> ResultRef("github_project_item", id)
    is GitHubEntity.Run -> ResultRef("github_run", id)
    is GitHubEntity.Release -> ResultRef("github_release", id)
    is GitHubEntity.Review -> ResultRef("github_review", id)
    is GitHubEntity.Artifact -> ResultRef("github_artifact", id)
}

private fun GitHubChange.simpleReference(body: JsonObject): ResultRef = when (this) {
    is GitHubChange.CreateIssue -> ResultRef("github_issue", body.text("number"))

    is GitHubChange.Comment -> ResultRef("github_comment", body.text("id"))

    is GitHubChange.AddSubIssue -> ResultRef("github_sub_issue", body.text("id"))

    is GitHubChange.CreatePull, is GitHubChange.AddProjectItem, is GitHubChange.Dispatch,
    is GitHubChange.Merge, is GitHubChange.CreateRelease,
    -> error("Dedicated GitHub reference required")
}
