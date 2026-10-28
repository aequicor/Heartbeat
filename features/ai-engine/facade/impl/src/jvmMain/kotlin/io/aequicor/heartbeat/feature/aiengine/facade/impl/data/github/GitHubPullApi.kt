package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.github

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8

/**
 * One repository; the host fixes the destination before authorization. Revision checks detect stale
 * reads but GitHub metadata endpoints have no atomic compare-and-set. These operations never certify a SHA,
 * approve a review, merge, push, dispatch workflows or publish a release. Repository automation may still run.
 */
internal class GitHubPullApi(private val transport: GitHubPullTransport) {
    private val log = Log.tag("GitHubPullApi")

    @HighFrequency
    suspend fun perform(configuration: GitHubPullConfiguration, operation: GitHubPullOperation): JsonObject {
        log.v { "GitHub PR metadata operation" }
        val actor = transport.call(configuration, "GET", "/user", null)
        check(actor["login"]?.jsonPrimitive?.content.equals(configuration.account, ignoreCase = true)) {
            "GitHub credential does not match the configured account"
        }
        val root = "/repos/${configuration.repository}"
        val repository = transport.call(configuration, "GET", root, null)
        check(
            repository["full_name"]?.jsonPrimitive?.content == configuration.repository,
        ) {
            "Only the configured repository is supported"
        }
        if (operation.name == "github_pr_create") {
            return summary(
                configuration,
                transport.call(configuration, "POST", "$root/pulls", operation.payload),
            )
        }
        return existing(configuration, operation, "$root/pulls/${requireNotNull(operation.number)}")
    }

    private suspend fun existing(
        configuration: GitHubPullConfiguration,
        operation: GitHubPullOperation,
        path: String,
    ): JsonObject {
        val before = transport.call(configuration, "GET", path, null)
        check(before["number"]?.jsonPrimitive?.intOrNull == operation.number) { "Pull request identity changed" }
        val current = summary(configuration, before)
        if (operation.isRead) return current
        check(current["revision"]?.jsonPrimitive?.content == operation.expected) {
            "Pull request changed; read again before proposing a new operation"
        }
        check(before["state"]?.jsonPrimitive?.content == "open") { "Pull request is not open" }
        return if (operation.name == "github_pr_update") {
            summary(configuration, transport.call(configuration, "PATCH", path, operation.payload))
        } else {
            check(operation.name == "github_pr_ready")
            if (before["draft"]?.jsonPrimitive?.booleanOrNull == false) current else ready(configuration, before)
        }
    }

    private suspend fun ready(configuration: GitHubPullConfiguration, before: JsonObject): JsonObject {
        check(before["draft"]?.jsonPrimitive?.booleanOrNull == true) { "Missing draft state" }
        val id = before["node_id"]?.jsonPrimitive?.content ?: error("Missing pull request identity")
        val response = transport.call(
            configuration,
            "POST",
            "/graphql",
            buildJsonObject {
                put("query", READY_MUTATION)
                put("variables", buildJsonObject { put("id", id) })
            },
        )
        check(response["errors"] == null) { "GitHub ready outcome is unknown; read before retry" }
        val result = response["data"]?.jsonObject?.get("markPullRequestReadyForReview")?.jsonObject
            ?.get("pullRequest")?.jsonObject
        check(
            result?.get("id")?.jsonPrimitive?.content == id &&
                result["isDraft"]?.jsonPrimitive?.booleanOrNull == false,
        ) {
            "GitHub ready outcome is unknown; read before retry"
        }
        return buildJsonObject {
            put("number", checkNotNull(before["number"]))
            put("ready", true)
            put("notice", "Metadata changed; this does not certify a commit. Read again for the current revision.")
        }
    }
}

/** Return only declared public fields, never arbitrary headers, transport bodies or GraphQL diagnostics. */
private fun summary(configuration: GitHubPullConfiguration, source: JsonObject): JsonObject {
    val number = source["number"]?.jsonPrimitive?.intOrNull ?: error("Invalid pull request")
    val baseRepository = source["base"]?.jsonObject?.get("repo")?.jsonObject
        ?.get("full_name")?.jsonPrimitive?.content
    check(baseRepository == configuration.repository && number > 0) { "Unexpected pull request repository" }
    val fields = buildJsonObject {
        put("repository", configuration.repository)
        put("number", number)
        for (field in listOf("title", "body", "state", "draft", "updated_at", "node_id")) {
            put(field, source[field] ?: JsonPrimitive(""))
        }
        put("head", source["head"]?.jsonObject?.get("sha") ?: error("Missing head SHA"))
        put("base", source["base"]?.jsonObject?.get("ref") ?: error("Missing base branch"))
    }
    check(fields.toString().length <= MAX_METADATA_CHARS) { "Pull request metadata exceeds the review limit" }
    return JsonObject(
        fields + mapOf(
            "revision" to JsonPrimitive(fields.toString().encodeUtf8().sha256().hex()),
            "url" to JsonPrimitive("https://github.com/${configuration.repository}/pull/$number"),
        ),
    )
}

private const val MAX_METADATA_CHARS = 256 * 1024

private const val READY_MUTATION =
    "mutation Ready(\$id:ID!){markPullRequestReadyForReview(input:{pullRequestId:\$id}){pullRequest{id isDraft}}}"
