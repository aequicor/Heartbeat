package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Closed metadata-only protocol. No URL, headers, credentials, shell, file references or arbitrary GraphQL
 * can be supplied by the model. Text is captured inline and transmitted without later filesystem reads.
 */
internal data class GitHubPullOperation(
    val name: String,
    val number: Int?,
    val expected: String?,
    val payload: JsonObject,
) {
    val isRead: Boolean get() = name == "github_pr_read"

    override fun toString(): String = "GitHubPullOperation($name)"

    companion object {
        fun parse(name: String, arguments: JsonObject): GitHubPullOperation {
            val fields = when (name) {
                "github_pr_read" -> setOf("number")
                "github_pr_create" -> setOf("title", "body", "head", "base")
                "github_pr_update" -> setOf("number", "expected_revision", "title", "body")
                "github_pr_ready" -> setOf("number", "expected_revision")
                else -> error("Unknown GitHub operation")
            }
            require(arguments.keys == fields) { "Supply exactly the declared fields; text must be inline" }
            val number = if ("number" in fields) {
                requireNotNull(arguments["number"]?.jsonPrimitive?.intOrNull).also {
                    require(it > 0) { "Invalid pull request number" }
                }
            } else {
                null
            }
            val expected = if ("expected_revision" in fields) {
                arguments.text("expected_revision", 64).also {
                    require(Regex("[0-9a-f]{64}").matches(it)) { "Read the current pull request revision first" }
                }
            } else {
                null
            }
            val payload = buildJsonObject {
                if ("title" in fields) {
                    put("title", arguments.text("title", 256).also { require(it.isNotBlank()) })
                    put("body", arguments.text("body", MAX_BODY_CHARS))
                }
                if (name == "github_pr_create") {
                    put("head", arguments.branch("head"))
                    put("base", arguments.branch("base"))
                    put("draft", true)
                    put("maintainer_can_modify", false)
                }
            }
            return GitHubPullOperation(name, number, expected, payload)
        }
    }
}

private fun JsonObject.text(key: String, maximum: Int): String {
    val value = get(key) as? JsonPrimitive
    require(value?.isString == true) { "Expected text" }
    return value.content.also {
        require(it.length <= maximum && '\u0000' !in it) { "Text exceeds the supported limit" }
    }
}

/** A conservative branch subset; forks and ref expressions require a separate reviewed capability. */
private fun JsonObject.branch(key: String): String = text(key, MAX_BRANCH_CHARS).also {
    require(Regex("[A-Za-z0-9][A-Za-z0-9/_-]*").matches(it) && !it.endsWith('/') && "//" !in it) {
        "Unsupported branch name"
    }
}

internal const val MAX_BODY_CHARS = 32 * 1024
private const val MAX_BRANCH_CHARS = 200
