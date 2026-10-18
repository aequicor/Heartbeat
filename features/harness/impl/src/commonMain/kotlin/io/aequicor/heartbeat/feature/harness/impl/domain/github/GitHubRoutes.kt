package io.aequicor.heartbeat.feature.harness.impl.domain.github

import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubQuery
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val GITHUB_PAGE_SIZE = 50
internal const val GITHUB_MAX_PAGES = 20

/** Validation precedes both reservation and external IO; reserved requests cannot change after review. */
internal fun GitHubChange.validate() {
    when (this) {
        is GitHubChange.CreateIssue -> validateText(title, body)

        is GitHubChange.CreatePull -> {
            validateText(title, body)
            require(validRef(branch) && validRef(base))
        }

        is GitHubChange.Comment -> {
            require(number > 0)
            validateBody(body)
        }

        is GitHubChange.AddSubIssue -> require(parent > 0 && child > 0)

        is GitHubChange.AddProjectItem -> Unit

        is GitHubChange.Dispatch -> validateDispatch()

        is GitHubChange.Merge -> require(number > 0)

        is GitHubChange.CreateRelease -> {
            require(validRef(tag))
            validateBody(body)
        }
    }
}

private fun validateText(title: String, body: String) {
    require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS)
    validateBody(body)
}
private fun validateBody(body: String) {
    require(body.length <= MAX_BODY_CHARS)
}
private fun GitHubChange.Dispatch.validateDispatch() {
    require(workflow > 0 && validRef(ref) && inputs.size <= MAX_INPUTS)
    require(inputs.keys.all { Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(it) })
    require(inputs.values.all { it.length <= MAX_INPUT_CHARS })
    require(correlationInput == null || correlationInput in inputs.keys)
}

/** Only unambiguous, bounded ref names; query delimiters and path traversal never reach an API route. */
internal fun validRef(value: String): Boolean = value.length in 1..MAX_REF_CHARS &&
    Regex("[A-Za-z0-9_][A-Za-z0-9_./-]*").matches(value) &&
    value.split('/').all { it.isNotEmpty() && it != "." && it != ".." } && ".." !in value

private fun page(value: Int): String {
    require(value in 1..GITHUB_MAX_PAGES) { "GitHub page limit" }
    return "per_page=$GITHUB_PAGE_SIZE&page=$value"
}

private fun number(value: Long): Long {
    require(value > 0) { "Invalid GitHub number" }
    return value
}

internal fun GitHubRepository.route(): String = "repos/$owner/$name"

internal fun GitHubQuery.request(repository: GitHubRepository): GitHubRequest {
    if (this is GitHubQuery.ProjectItems) return projectQuery(this)
    return restRequest(repository)
}

private fun GitHubQuery.restRequest(repository: GitHubRepository): GitHubRequest {
    val root = repository.route()
    val path = when (this) {
        is GitHubQuery.Issue -> "$root/issues/${number(number)}"
        is GitHubQuery.Issues -> "$root/issues?state=all&sort=updated&direction=desc&${page(page)}"
        is GitHubQuery.SubIssues -> "$root/issues/${number(number)}/sub_issues?${page(page)}"
        is GitHubQuery.Pull -> "$root/pulls/${number(number)}"
        is GitHubQuery.Pulls -> "$root/pulls?state=all&sort=updated&direction=desc&${page(page)}"
        is GitHubQuery.Comments -> "$root/issues/${number(number)}/comments?${page(page)}"
        is GitHubQuery.Reviews -> "$root/pulls/${number(number)}/reviews?${page(page)}"
        is GitHubQuery.Runs -> "$root/actions/workflows/${number(workflow)}/runs?event=workflow_dispatch&${page(page)}"
        is GitHubQuery.Run -> "$root/actions/runs/${number(id)}"
        is GitHubQuery.Artifacts -> "$root/actions/runs/${number(run)}/artifacts?${page(page)}"
        is GitHubQuery.Releases -> "$root/releases?${page(page)}"
        is GitHubQuery.Commit, is GitHubQuery.Tag -> refPath(root)
        is GitHubQuery.ProjectItems -> error("Project route uses GraphQL")
    }
    return GitHubRequest("GET", path)
}

private fun GitHubQuery.refPath(root: String): String = when (this) {
    is GitHubQuery.Commit -> {
        require(validRef(ref))
        "$root/commits/$ref"
    }

    is GitHubQuery.Tag -> {
        require(validRef(name))
        "$root/git/ref/tags/$name"
    }

    is GitHubQuery.Issue, is GitHubQuery.Issues, is GitHubQuery.SubIssues, is GitHubQuery.Pull,
    is GitHubQuery.Pulls, is GitHubQuery.Comments, is GitHubQuery.Reviews, is GitHubQuery.Runs,
    is GitHubQuery.Run, is GitHubQuery.Artifacts, is GitHubQuery.Releases,
    is GitHubQuery.ProjectItems,
    -> error("A GitHub ref route is required")
}

private fun projectQuery(query: GitHubQuery.ProjectItems): GitHubRequest {
    require((query.after?.length ?: 0) <= MAX_CURSOR_CHARS) { "Invalid project cursor" }
    return GitHubRequest(
        "POST",
        "graphql",
        buildJsonObject {
            put("query", PROJECT_QUERY)
            put(
                "variables",
                buildJsonObject {
                    put("project", query.project.value)
                    put("after", query.after?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        },
    )
}

/** Correlation is appended to text, not treated as an API idempotency guarantee. */
internal fun GitHubChange.request(repository: GitHubRepository, marker: String): GitHubRequest {
    val root = repository.route()
    var method = "POST"
    val path = when (this) {
        is GitHubChange.CreateIssue -> "$root/issues"

        is GitHubChange.CreatePull -> "$root/pulls"

        is GitHubChange.Comment -> "$root/issues/$number/comments"

        is GitHubChange.AddSubIssue -> "$root/issues/$parent/sub_issues"

        is GitHubChange.AddProjectItem -> "graphql"

        is GitHubChange.Dispatch -> "$root/actions/workflows/$workflow/dispatches"

        is GitHubChange.Merge -> {
            method = "PUT"
            "$root/pulls/$number/merge"
        }

        is GitHubChange.CreateRelease -> "$root/releases"
    }
    return GitHubRequest(method, path, payload(marker))
}

private fun GitHubChange.payload(marker: String) = buildJsonObject {
    when (val change = this@payload) {
        is GitHubChange.CreateIssue -> {
            put("title", change.title)
            put("body", change.body.marked(marker))
        }

        is GitHubChange.CreatePull -> {
            put("title", change.title)
            put("body", change.body.marked(marker))
            put("head", change.branch)
            put("base", change.base)
            put("draft", true)
        }

        is GitHubChange.Comment -> put("body", change.body.marked(marker))

        is GitHubChange.AddSubIssue -> put("sub_issue_id", change.child)

        is GitHubChange.AddProjectItem -> {
            put("query", PROJECT_MUTATION)
            put(
                "variables",
                buildJsonObject {
                    put("project", change.project.value)
                    put("content", change.content.value)
                    put("marker", marker)
                },
            )
        }

        is GitHubChange.Dispatch -> {
            put("ref", change.ref)
            put("return_run_details", true)
            put(
                "inputs",
                buildJsonObject {
                    change.inputs.forEach { (key, value) -> put(key, value) }
                    change.correlationInput?.let { put(it, marker) }
                },
            )
        }

        is GitHubChange.Merge -> {
            put("sha", change.head.value)
            put("merge_method", "squash")
        }

        is GitHubChange.CreateRelease -> {
            put("tag_name", change.tag)
            put("target_commitish", change.head.value)
            put("body", change.body.marked(marker))
            put("draft", true)
        }
    }
}

internal fun String.marked(marker: String): String = "$this\n\n<!-- hb-operation:$marker -->"

internal fun GitHubQuery.pageNumber(): Int? = when (this) {
    is GitHubQuery.Issues -> page

    is GitHubQuery.SubIssues -> page

    is GitHubQuery.Pulls -> page

    is GitHubQuery.Comments -> page

    is GitHubQuery.Reviews -> page

    is GitHubQuery.Runs -> page

    is GitHubQuery.Artifacts -> page

    is GitHubQuery.Releases -> page

    is GitHubQuery.Issue, is GitHubQuery.Pull, is GitHubQuery.Run,
    is GitHubQuery.Commit, is GitHubQuery.Tag, is GitHubQuery.ProjectItems,
    -> null
}

private const val PROJECT_QUERY = """
query(${'$'}project:ID!, ${'$'}after:String) {
  node(id:${'$'}project) { ... on ProjectV2 {
    items(first:50, after:${'$'}after) {
      nodes { id updatedAt content { ... on Issue { id } ... on PullRequest { id } } }
      pageInfo { hasNextPage endCursor }
    }
  } }
}
"""
private const val PROJECT_MUTATION = """
mutation(${'$'}project:ID!, ${'$'}content:ID!, ${'$'}marker:String!) {
  addProjectV2ItemById(input:{projectId:${'$'}project, contentId:${'$'}content, clientMutationId:${'$'}marker}) {
    item { id }
  }
}
"""

private const val MAX_TITLE_CHARS = 256
private const val MAX_BODY_CHARS = 16_384
private const val MAX_INPUTS = 10
private const val MAX_INPUT_CHARS = 1024
private const val MAX_CURSOR_CHARS = 1024
private const val MAX_REF_CHARS = 200
