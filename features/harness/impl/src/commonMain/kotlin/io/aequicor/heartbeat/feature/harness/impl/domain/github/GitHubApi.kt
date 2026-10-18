package io.aequicor.heartbeat.feature.harness.impl.domain.github

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.github.GitHubEntity
import io.aequicor.heartbeat.feature.harness.api.github.GitHubFailure
import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubQuery
import io.aequicor.heartbeat.feature.harness.api.github.GitHubReadResult
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubSnapshot
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Profile-shared bounded reads and backoff. No automatic retry of writes is performed here. */
internal class GitHubApi(private val transport: GitHubTransport, private val clock: Clock) {
    private val log = Log.tag("GitHubApi")
    private val gate = Mutex()
    private var retryAt: Instant? = null
    private var failures = 0
    val isAvailable: Boolean get() = transport.isAvailable

    @HighFrequency
    suspend fun call(
        request: GitHubRequest,
        isAdmitted: suspend () -> Boolean = {
            true
        },
    ): GitHubResponse = gate.withLock {
        log.v { "GitHub bounded API call" }
        if (retryAt?.let { clock.now() < it } == true) return@withLock GitHubResponse(0)
        if (!isAdmitted()) return@withLock GitHubResponse(0)
        val response = transport.call(request)
        if (response.requiresBackoff()) {
            failures = (failures + 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
            val retrySeconds = response.headers["retry-after"]?.toLongOrNull()?.coerceIn(1, MAX_RETRY_SECONDS)
            val reset = response.headers["x-ratelimit-reset"]?.toLongOrNull()?.let(Instant::fromEpochSeconds)
            val fallback = clock.now() + (1L shl failures).seconds
            retryAt = when {
                retrySeconds != null -> clock.now() + retrySeconds.seconds

                response.headers["x-ratelimit-remaining"] == "0" && reset != null -> maxOf(reset, fallback)

                response.status == GitHubHttp.FORBIDDEN || response.status == GitHubHttp.RATE_LIMITED -> clock.now() +
                    RATE_LIMIT_SECONDS.seconds

                else -> fallback
            }
        } else {
            failures = 0
            retryAt = null
        }
        response
    }

    suspend fun read(repository: GitHubRepository, query: GitHubQuery): GitHubReadResult {
        val response = call(query.request(repository))
        if (response.status !in GitHubHttp.SUCCESS) return failure(response)
        return try {
            if (query is GitHubQuery.Commit) {
                GitHubReadResult.Read(
                    GitHubSnapshot(commit = SubjectSha(checkNotNull(response.body).jsonObject.text("sha"))),
                )
            } else if (query is GitHubQuery.Tag) {
                readTag(repository, response)
            } else {
                GitHubReadResult.Read(query.decode(response))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = IllegalStateException("GitHub response could not be decoded")
            log.w(failure) { "GitHub response rejected (${error::class.simpleName.orEmpty()})" }
            GitHubReadResult.Failed(GitHubFailure.InvalidResponse)
        }
    }

    private fun failure(response: GitHubResponse): GitHubReadResult.Failed = GitHubReadResult.Failed(
        when (response.status) {
            GitHubHttp.UNAUTHORIZED -> GitHubFailure.Access

            GitHubHttp.FORBIDDEN -> if (response.headers["x-ratelimit-remaining"] == "0" ||
                "retry-after" in response.headers
            ) {
                GitHubFailure.RateLimited
            } else {
                GitHubFailure.Access
            }

            GitHubHttp.NOT_FOUND -> GitHubFailure.NotFound

            GitHubHttp.RATE_LIMITED -> GitHubFailure.RateLimited

            else -> GitHubFailure.Unavailable
        },
        retryAt,
    )

    private suspend fun readTag(repository: GitHubRepository, first: GitHubResponse): GitHubReadResult {
        var obj = checkNotNull(first.body).jsonObject.obj("object")
        repeat(MAX_TAG_DEPTH) {
            val sha = SubjectSha(obj.text("sha"))
            if (obj.text("type") == "commit") return GitHubReadResult.Read(GitHubSnapshot(commit = sha))
            check(obj.text("type") == "tag")
            val response = call(GitHubRequest("GET", "${repository.route()}/git/tags/${sha.value}"))
            if (response.status !in GitHubHttp.SUCCESS) return failure(response)
            obj = checkNotNull(response.body).jsonObject.obj("object")
        }
        error("GitHub tag depth exceeded")
    }
}

private fun GitHubQuery.decode(response: GitHubResponse): GitHubSnapshot {
    val body = checkNotNull(response.body)
    if (this is GitHubQuery.ProjectItems) return decodeProject(body.jsonObject)
    val values = when (this) {
        is GitHubQuery.Issue, is GitHubQuery.Pull, is GitHubQuery.Run -> listOf(body)

        is GitHubQuery.Runs -> body.jsonObject.getValue("workflow_runs") as JsonArray

        is GitHubQuery.Artifacts -> body.jsonObject.getValue("artifacts") as JsonArray

        is GitHubQuery.Issues, is GitHubQuery.SubIssues, is GitHubQuery.Pulls,
        is GitHubQuery.Comments, is GitHubQuery.Reviews, is GitHubQuery.Releases,
        -> body as JsonArray

        is GitHubQuery.Commit, is GitHubQuery.Tag, is GitHubQuery.ProjectItems -> error("Dedicated decoder required")
    }
    check(values.size <= GITHUB_PAGE_SIZE || pageNumber() == null)
    val entities = values.mapNotNull { value -> decodeEntity(value.jsonObject) }
    val next = pageNumber()?.let { current ->
        // Headers alone establish completeness, including pages whose issues are all filtered PRs.
        (current + 1).takeIf { response.headers["link"]?.contains("rel=\"next\"") == true }
    }
    return GitHubSnapshot(entities, nextPage = next)
}

private fun GitHubQuery.decodeEntity(obj: JsonObject): GitHubEntity? = when (this) {
    is GitHubQuery.Issue, is GitHubQuery.Issues, is GitHubQuery.SubIssues ->
        if ("pull_request" in obj) {
            null
        } else {
            GitHubEntity.Issue(
                obj.text("id"),
                obj.instant("updated_at"),
                obj.text("number").toLong(),
                GitHubNodeId(obj.text("node_id")),
                obj.text("title"),
                obj.optional("body").orEmpty(),
                obj.text("state"),
            )
        }

    is GitHubQuery.Pull, is GitHubQuery.Pulls -> GitHubEntity.Pull(
        obj.text("id"), obj.instant("updated_at"), obj.text("number").toLong(), SubjectSha(obj.obj("head").text("sha")),
        obj.obj("base").text("ref"), obj.obj("head").text("ref"), obj.optional("body").orEmpty(),
        obj.text("state"), obj.flag("draft"),
    )

    is GitHubQuery.Comments -> GitHubEntity.Comment(obj.text("id"), obj.instant("updated_at"), obj.text("body"))

    is GitHubQuery.Reviews -> GitHubEntity.Review(
        obj.text("id"),
        obj.optional("submitted_at")?.let(Instant::parse) ?: Instant.DISTANT_PAST,
        SubjectSha(obj.text("commit_id")),
        obj.text("state"),
    )

    is GitHubQuery.Run, is GitHubQuery.Runs -> GitHubEntity.Run(
        obj.text("id"),
        obj.instant("updated_at"),
        obj.text("workflow_id").toLong(),
        SubjectSha(obj.text("head_sha")),
        obj.text("event"),
        obj.text("status"),
        obj.optional("conclusion"),
        obj.text("display_title"),
    )

    is GitHubQuery.Artifacts -> GitHubEntity.Artifact(
        obj.text("id"),
        obj.instant("updated_at"),
        obj.text("name"),
        obj.flag("expired"),
        run.toString(),
    )

    is GitHubQuery.Releases -> GitHubEntity.Release(
        obj.text("id"),
        obj.instant("updated_at"),
        obj.text("tag_name"),
        obj.text("target_commitish"),
        obj.optional("body").orEmpty(),
        obj.flag("draft"),
        (obj.getValue("assets") as JsonArray).map { it.jsonObject.text("name") },
    )

    is GitHubQuery.ProjectItems, is GitHubQuery.Tag, is GitHubQuery.Commit -> error("Dedicated GitHub decoder required")
}

private fun decodeProject(body: JsonObject): GitHubSnapshot {
    check("errors" !in body) { "GraphQL read failed" }
    val items = body.obj("data").obj("node").obj("items")
    val entries = (items.getValue("nodes") as JsonArray).map { entry ->
        val obj = entry.jsonObject
        GitHubEntity.ProjectItem(
            obj.text("id"),
            obj.instant("updatedAt"),
            (obj["content"] as? JsonObject)?.optional("id")?.let(::GitHubNodeId),
        )
    }
    check(entries.size <= GITHUB_PAGE_SIZE)
    val info = items.obj("pageInfo")
    val cursor = if (info.flag("hasNextPage")) info.text("endCursor") else null
    return GitHubSnapshot(entries, nextCursor = cursor)
}

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.optional(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
private fun JsonObject.instant(key: String): Instant = Instant.parse(text(key))
private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

private fun GitHubResponse.requiresBackoff(): Boolean =
    status in setOf(0, GitHubHttp.RATE_LIMITED, GitHubHttp.FORBIDDEN) ||
        status >= GitHubHttp.SERVER_ERROR || headers["x-ratelimit-remaining"] == "0"

private const val MAX_BACKOFF_EXPONENT = 8
private const val MAX_RETRY_SECONDS = 3600L
private const val RATE_LIMIT_SECONDS = 60
private const val MAX_TAG_DEPTH = 4
