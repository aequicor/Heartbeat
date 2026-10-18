package io.aequicor.heartbeat.feature.harness.impl.data.ownership.github

import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.PortableEvidence
import io.aequicor.heartbeat.feature.harness.api.process.PortableGitHubKind
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubApi
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubOperation
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8

/** Host-independent request identity: native workspace, decision id and native approval are deliberately absent. */
internal fun GitHubOperation.portablePayloadDigest(repositoryNode: GitHubNodeId): String = buildJsonObject {
    put("version", 1)
    put("repository", repositoryNode.value)
    put("revision", inputRevision)
    put("change", Json.encodeToJsonElement(change))
    put("attempt", context?.let { Json.encodeToJsonElement(it.attempt) } ?: JsonNull)
}.canonicalDigest()

/** Payload is transient and never serialized into a portable checkpoint or included in a log. */
internal data class LoadedPortableEvidence(val evidence: PortableEvidence, val observation: JsonObject) {
    override fun toString(): String = "LoadedPortableEvidence(***)"
}

/** Fixed numeric read routes, independently bound to the server repository node before every evidence read. */
internal class PortableGitHubEvidenceReader(private val api: GitHubApi) {
    suspend fun repositoryNode(repository: GitHubRepository): GitHubNodeId? {
        val response = api.call(GitHubRequest("GET", "repos/${repository.owner}/${repository.name}"))
        val node = (response.body as? JsonObject)?.text("node_id")
        return if (response.status == OK && node != null) GitHubNodeId(node) else null
    }

    suspend fun read(repository: GitHubRepository, kind: PortableGitHubKind, number: Long): LoadedPortableEvidence? {
        val route = kind.route(number)
        return if (route == null) {
            null
        } else {
            repositoryNode(repository)?.let { read(repository, kind, number, route, it) }
        }
    }

    private suspend fun read(
        repository: GitHubRepository,
        kind: PortableGitHubKind,
        number: Long,
        route: String,
        node: GitHubNodeId,
    ): LoadedPortableEvidence? {
        val response = api.call(GitHubRequest("GET", "repos/${repository.owner}/${repository.name}/$route"))
        val body = response.body as? JsonObject
        return if (response.status != OK || body == null || !kind.validIdentity(body, number)) {
            null
        } else {
            kind.facts(body)?.let { facts ->
                val digest = buildJsonObject {
                    put("version", 1)
                    put("repository", node.value)
                    put("kind", Json.encodeToJsonElement(kind))
                    put("number", number)
                    put("facts", facts)
                }.canonicalDigest()
                LoadedPortableEvidence(PortableEvidence(repository, node, kind, number, digest), body)
            }
        }
    }

    suspend fun verify(evidence: PortableEvidence): Boolean = read(evidence.repository, evidence.kind, evidence.number)
        ?.evidence?.let {
            it.repositoryNode == evidence.repositoryNode && it.digest == evidence.digest
        } == true
}

private fun PortableGitHubKind.route(number: Long): String? = when (this) {
    PortableGitHubKind.Issue -> "issues/$number"

    PortableGitHubKind.Pull -> "pulls/$number"

    PortableGitHubKind.IssueComment -> "issues/comments/$number"

    PortableGitHubKind.Run -> "actions/runs/$number"

    // Review needs its parent PR; release needs independently peeled tag proof. The v1 locator lacks these bindings.
    PortableGitHubKind.Review, PortableGitHubKind.Release -> null
}

private fun PortableGitHubKind.validIdentity(body: JsonObject, number: Long): Boolean {
    val hasIdentity = body.long("id")?.let { it > 0 } == true && body.text("node_id") != null
    val isNumbered = this == PortableGitHubKind.Issue || this == PortableGitHubKind.Pull
    val isSame = if (isNumbered) body.long("number") == number else body.long("id") == number
    return hasIdentity && isSame && (this != PortableGitHubKind.Issue || "pull_request" !in body)
}

private fun PortableGitHubKind.facts(body: JsonObject): JsonObject? {
    if (this == PortableGitHubKind.Run && !body.isCompletedRun()) return null
    val names = when (this) {
        PortableGitHubKind.Issue -> listOf("id", "node_id", "number", "title", "body", "state")

        PortableGitHubKind.Pull -> listOf(
            "id",
            "node_id",
            "number",
            "title",
            "body",
            "state",
            "merged",
            "merge_commit_sha",
        )

        PortableGitHubKind.IssueComment -> listOf("id", "node_id", "body")

        PortableGitHubKind.Run -> listOf(
            "id", "node_id", "workflow_id", "head_sha", "event", "status", "conclusion", "display_title", "run_attempt",
        )

        PortableGitHubKind.Review, PortableGitHubKind.Release -> emptyList()
    }
    return when {
        names.isEmpty() || names.any { it !in body || body[it] !is JsonPrimitive } -> null
        this == PortableGitHubKind.Pull -> body.pullFacts(names)
        else -> JsonObject(names.associateWith { body.getValue(it) })
    }
}

private fun JsonObject.isCompletedRun(): Boolean = text("status") == "completed" && text("conclusion") != null

private fun JsonObject.pullFacts(names: List<String>): JsonObject? {
    val head = obj("head")
    val base = obj("base")
    if (head == null || base == null) return null
    return if (head.hasHeadFacts() && base.text("ref") != null) {
        JsonObject(
            names.associateWith { getValue(it) } + mapOf(
                "head_sha" to head.getValue("sha"),
                "head_ref" to head.getValue("ref"),
                "base_ref" to base.getValue("ref"),
            ),
        )
    } else {
        null
    }
}

private fun JsonObject.hasHeadFacts(): Boolean = text("sha") != null && text("ref") != null

internal fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
internal fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonElement.canonicalDigest(): String = canonical().toString().encodeUtf8().sha256().hex()
private fun JsonElement.canonical(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { it.key to it.value.canonical() })
    is JsonArray -> JsonArray(map { it.canonical() })
    is JsonPrimitive -> this
}

private const val OK = 200
