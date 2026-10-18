package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubApi
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipBackend
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipCommit
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipRead
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8

/**
 * Conditional GitHub Contents writes on a dedicated service branch. The previous blob SHA is mandatory for
 * update; a competing create/update conflicts. Every value contains an increasing revision, so bytes never ABA
 * under this protocol. Neither comments nor labels confer ownership. Repository administrators can bypass this
 * cooperative protocol; old clients/direct GitHub calls and already dispatched external actions are not fenced.
 *
 * Reads are observations, never admission. Even a stale GET cannot authorize dispatch: the host must successfully
 * CAS a durable permit into this SAME document and hold it until confirmed completion. No uncertain PUT retries.
 */
internal class GitHubOwnershipBackend(private val api: GitHubApi, repository: GitHubRepository) : OwnershipBackend {
    private val log = Log.tag("GitHubOwnership")
    private val root = "repos/${repository.owner}/${repository.name}"

    /** Host-approved setup only. Ordinary reads never create a branch or write repository data. */
    suspend fun prepare(): Boolean {
        log.i { "prepare process ownership coordinator" }
        val default = configuredDefault() ?: return false
        val branch = api.call(GitHubRequest("GET", "$root/git/ref/heads/$OWNERSHIP_BRANCH"))
        return when (branch.status) {
            OK -> branch.body.isCommitRef()
            NOT_FOUND -> createBranch(default)
            else -> false
        }
    }

    private suspend fun configuredDefault(): String? {
        val response = api.call(GitHubRequest("GET", root))
        val metadata = response.body as? JsonObject
        val permissions = metadata?.get("permissions") as? JsonObject
        val isPushAllowed = (permissions?.get("push") as? JsonPrimitive)?.booleanOrNull == true
        val default = metadata?.string("default_branch")
        val isConfigured = response.status == OK && isPushAllowed && default != OWNERSHIP_BRANCH
        return default?.takeIf { isConfigured && it.isSafeRef() }
    }

    private suspend fun createBranch(default: String): Boolean {
        val base = api.call(GitHubRequest("GET", "$root/git/ref/heads/$default"))
        val sha = ((base.body as? JsonObject)?.get("object") as? JsonObject)?.string("sha")
        if (base.status != OK || !base.body.isCommitRef() || sha == null) return false
        val created = api.call(
            GitHubRequest(
                "POST",
                "$root/git/refs",
                buildJsonObject {
                    put("ref", "refs/heads/$OWNERSHIP_BRANCH")
                    put("sha", sha)
                },
            ),
        )
        return if (created.status in setOf(OK, CREATED, CONFLICT, VALIDATION, 0)) {
            // A lost response or concurrent creator never causes deletion/recreation.
            val confirmed = api.call(GitHubRequest("GET", "$root/git/ref/heads/$OWNERSHIP_BRANCH"))
            confirmed.status == OK && confirmed.body.isCommitRef()
        } else {
            false
        }
    }

    @HighFrequency
    override suspend fun read(key: OwnershipKey): OwnershipRead {
        log.v { "read process ownership coordinator" }
        return when (val loaded = load(path(key))) {
            is Loaded.Failed -> OwnershipRead.Unavailable(loaded.reason)

            Loaded.Absent -> OwnershipRead.Current(null)

            is Loaded.File -> guardedRead {
                val stored = json.decodeFromString<StoredOwnership>(loaded.content)
                check(stored.version == VERSION && stored.record.fence.key == key)
                OwnershipRead.Current(stored.record)
            }
        }
    }

    @HighFrequency
    override suspend fun compareAndSet(
        key: OwnershipKey,
        expected: ProcessOwnership?,
        next: ProcessOwnership,
    ): OwnershipCommit {
        log.v { "conditionally write process ownership coordinator" }
        if (!isValidNext(key, expected, next)) {
            return OwnershipCommit.Unavailable(OwnershipUnavailable.InvalidRecord)
        }
        return when (val loaded = load(path(key))) {
            is Loaded.Failed -> OwnershipCommit.Unavailable(loaded.reason)
            Loaded.Absent -> if (expected == null) writeNext(key, next, null) else OwnershipCommit.Conflict
            is Loaded.File -> compareStored(key, expected, next, loaded)
        }
    }

    private suspend fun compareStored(
        key: OwnershipKey,
        expected: ProcessOwnership?,
        next: ProcessOwnership,
        file: Loaded.File,
    ): OwnershipCommit = when (
        val read = guardedRead {
            val stored = json.decodeFromString<StoredOwnership>(file.content)
            check(stored.version == VERSION && stored.record.fence.key == key)
            OwnershipRead.Current(stored.record)
        }
    ) {
        is OwnershipRead.Unavailable -> OwnershipCommit.Unavailable(read.reason)

        is OwnershipRead.Current -> if (read.record == expected) {
            writeNext(key, next, file.sha)
        } else {
            OwnershipCommit.Conflict
        }
    }

    private suspend fun writeNext(key: OwnershipKey, next: ProcessOwnership, sha: String?): OwnershipCommit {
        val bytes = json.encodeToString(StoredOwnership(VERSION, next))
        check(bytes.encodeToByteArray().size <= MAX_REGISTER_BYTES) { "Ownership register limit" }
        return put(path(key), bytes, sha)
    }

    /** Immutable allowlisted checkpoint bytes; callers must verify the content digest before importing. */
    suspend fun storeCheckpoint(digest: String, content: String): Boolean {
        require(DIGEST.matches(digest) && content.encodeToByteArray().size <= MAX_CHECKPOINT_BYTES)
        require(content.encodeUtf8().sha256().hex() == digest)
        val path = checkpointPath(digest)
        return when (val loaded = load(path, MAX_CHECKPOINT_BYTES)) {
            is Loaded.File -> loaded.content == content

            is Loaded.Failed -> false

            Loaded.Absent -> {
                put(path, content, null)
                // Including after an unknown response, only immutable bytes read back establish this artifact.
                (load(path, MAX_CHECKPOINT_BYTES) as? Loaded.File)?.content == content
            }
        }
    }

    suspend fun checkpoint(digest: String): String? {
        require(DIGEST.matches(digest))
        val file = load(checkpointPath(digest), MAX_CHECKPOINT_BYTES) as? Loaded.File ?: return null
        return file.content.takeIf { it.encodeUtf8().sha256().hex() == digest }
    }

    private suspend fun load(path: String, limit: Int = MAX_REGISTER_BYTES): Loaded {
        val response = api.call(GitHubRequest("GET", "$root/contents/$path?ref=$OWNERSHIP_BRANCH"))
        if (response.status == NOT_FOUND) {
            // Private/inaccessible repository and missing branch must never masquerade as an unclaimed key.
            val repo = api.call(GitHubRequest("GET", root))
            val branch = api.call(GitHubRequest("GET", "$root/git/ref/heads/$OWNERSHIP_BRANCH"))
            return if (repo.status == OK && branch.status == OK && branch.body.isCommitRef()) {
                Loaded.Absent
            } else {
                Loaded.Failed(OwnershipUnavailable.NotConfigured)
            }
        }
        if (response.status != OK) return Loaded.Failed(OwnershipUnavailable.Transport)
        return try {
            val obj = response.body as? JsonObject ?: error("Missing ownership content")
            check(obj.string("type") == "file" && obj.string("encoding") == "base64")
            check("target" !in obj && "submodule_git_url" !in obj)
            val encoded = checkNotNull(obj.string("content"))
            check(encoded.length <= limit * 2)
            val bytes = checkNotNull(encoded.replace("\n", "").decodeBase64())
            check(bytes.size <= limit)
            val sha = checkNotNull(obj.string("sha"))
            check(SHA.matches(sha) && blobHash(bytes.utf8(), sha.length) == sha)
            Loaded.File(bytes.utf8(), sha)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException("Invalid ownership coordinator content")) {
                "ownership response rejected (${error::class.simpleName.orEmpty()})"
            }
            Loaded.Failed(OwnershipUnavailable.InvalidRecord)
        }
    }

    private suspend fun put(path: String, content: String, previous: String?): OwnershipCommit {
        // Recheck on every mutation, including callers which did not invoke prepare().
        if (configuredDefault() == null) return OwnershipCommit.Unavailable(OwnershipUnavailable.NotConfigured)
        val response = api.call(
            GitHubRequest(
                "PUT",
                "$root/contents/$path",
                buildJsonObject {
                    put("message", "heartbeat: update process coordination")
                    put("branch", OWNERSHIP_BRANCH)
                    put("content", content.encodeUtf8().base64())
                    if (previous != null) put("sha", previous)
                },
            ),
        )
        return when (response.status) {
            CONFLICT, VALIDATION -> OwnershipCommit.Conflict
            OK, CREATED -> acknowledge(content, response.body as? JsonObject)
            else -> OwnershipCommit.Unavailable(OwnershipUnavailable.UnknownCommit)
        }
    }

    private fun acknowledge(content: String, body: JsonObject?): OwnershipCommit {
        val sha = (body?.get("content") as? JsonObject)?.string("sha")
        return if (sha != null && SHA.matches(sha) && blobHash(content, sha.length) == sha) {
            OwnershipCommit.Applied
        } else {
            OwnershipCommit.Unavailable(OwnershipUnavailable.UnknownCommit)
        }
    }

    private inline fun guardedRead(read: () -> OwnershipRead): OwnershipRead = try {
        read()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Invalid ownership register")) {
            "ownership register rejected (${error::class.simpleName.orEmpty()})"
        }
        OwnershipRead.Unavailable(OwnershipUnavailable.InvalidRecord)
    }
}

@Serializable
private data class StoredOwnership(val version: Int, val record: ProcessOwnership)

private sealed interface Loaded {
    data object Absent : Loaded
    data class File(val content: String, val sha: String) : Loaded {
        override fun toString(): String = "Loaded.File(***)"
    }
    data class Failed(val reason: OwnershipUnavailable) : Loaded
}

private fun kotlinx.serialization.json.JsonElement?.isCommitRef(): Boolean {
    val obj = (this as? JsonObject)?.get("object") as? JsonObject ?: return false
    return obj.string("type") == "commit" && obj.string("sha")?.let { SHA.matches(it) } == true
}
private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.content

internal fun blobHash(content: String, length: Int = 40): String {
    val data = content.encodeUtf8()
    val blob = Buffer().writeUtf8("blob ${data.size}\u0000").write(data).readByteString()
    return if (length == SHA256_LENGTH) blob.sha256().hex() else blob.sha1().hex()
}

/** Fixed service branch, displayed in native consent before the host prepares it. Never the default branch. */
internal const val OWNERSHIP_BRANCH = "heartbeat-processes"
private fun path(key: OwnershipKey): String = ".heartbeat/ownership/v1/${key.value}.json"
private fun checkpointPath(digest: String): String = ".heartbeat/ownership/checkpoints/$digest.json"
private const val VERSION = 1
private const val MAX_REGISTER_BYTES = 64 * 1024
private const val MAX_CHECKPOINT_BYTES = 128 * 1024
private const val OK = 200
private const val CREATED = 201
private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val VALIDATION = 422
private val REF = Regex("[A-Za-z0-9_./-]{1,200}")
private val SHA = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
private val DIGEST = Regex("[0-9a-f]{64}")
private val json = Json { encodeDefaults = true }

private const val SHA256_LENGTH = 64
private fun String.isSafeRef(): Boolean = REF.matches(this) && split('/').none { it == "." || it == ".." }

private fun isValidNext(key: OwnershipKey, expected: ProcessOwnership?, next: ProcessOwnership): Boolean {
    if (next.fence.key != key) return false
    return if (expected == null) {
        next.isFresh()
    } else {
        expected.revision < Long.MAX_VALUE && next.revision == expected.revision + 1 &&
            next.nextPermit >= expected.nextPermit
    }
}

private fun ProcessOwnership.isFresh(): Boolean {
    val hasFreshCounters = revision == 1L && nextPermit == 1L && fence.epoch == 1L
    val hasFreshState = phase == OwnershipPhase.Active && transfer == null && permits.isEmpty()
    return hasFreshCounters && hasFreshState
}
