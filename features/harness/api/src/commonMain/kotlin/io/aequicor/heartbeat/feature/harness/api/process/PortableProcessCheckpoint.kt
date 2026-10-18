package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteRefusal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** External task identity chosen and verified by the host, independent of local process and profile ids. */
@Serializable
public data class GitHubTaskIdentity(
    val repository: GitHubRepository,
    val kind: GitHubTaskKind,
    val number: Long,
    val repositoryNode: GitHubNodeId,
    val taskNode: GitHubNodeId,
) {
    init {
        require(number > 0)
    }

    /**
     * Versioned canonical input to the host's SHA-256 key derivation, stable across repository rename or transfer.
     * Display repository and task number are separate locators; the host verifies both against these server nodes.
     */
    public val canonical: String
        get() {
            val namespace = when (kind) {
                GitHubTaskKind.Issue -> "issues"
                GitHubTaskKind.Pull -> "pulls"
            }
            return "github-task-v1\n${repositoryNode.value}\n$namespace\n${taskNode.value}"
        }

    override fun toString(): String = "GitHubTaskIdentity(***)"
}

/** The two independently numbered task namespaces supported by the portable format. */
@Serializable
public enum class GitHubTaskKind {
    @SerialName("issue")
    Issue,

    @SerialName("pull")
    Pull,
}

/** Fixed github.com API resource kinds; no URL, free text or local resource reference is admitted. */
@Serializable
public enum class PortableGitHubKind {
    @SerialName("issue")
    Issue,

    @SerialName("pull")
    Pull,

    @SerialName("issue_comment")
    IssueComment,

    @SerialName("review")
    Review,

    @SerialName("run")
    Run,

    @SerialName("release")
    Release,
}

/** A remote locator plus digest of evidence sealed by the trusted host, never proof of fresh remote state. */
@Serializable
public data class PortableEvidence(
    val repository: GitHubRepository,
    val repositoryNode: GitHubNodeId,
    val kind: PortableGitHubKind,
    val number: Long,
    val digest: String,
) {
    init {
        require(number > 0 && isPortableDigest(digest))
    }

    /** Resolves only through the recipient's protected imported-evidence catalog. */
    public fun asResultRef(): ResultRef = ResultRef("handover_evidence", digest)

    override fun toString(): String = "PortableEvidence(***)"
}

/** No-eviction tombstone for a GitHub registry key, including entries older than ProcessRecord's write window. */
@Serializable
public data class PortableGitHubTombstone(
    val key: WriteKey,
    val inputRevision: Long,
    val payloadDigest: String,
    val marker: String,
    val result: PortableEvidence? = null,
    val refusal: GitHubWriteRefusal? = null,
) {
    init {
        require(inputRevision >= 0 && isPortableDigest(payloadDigest))
        require(Regex("[0-9a-f]{32}").matches(marker))
        require((result == null) != (refusal == null)) { "Unresolved GitHub operation" }
    }

    override fun toString(): String = "PortableGitHubTombstone(***)"
}

/** Closed attempt without an executor id, workflow journal, script source, prompt or result payload. */
@Serializable
public data class PortableProcessAttempt(
    val number: Int,
    val stage: StageName,
    val inputRevision: Long,
    val subject: SubjectSha?,
    val startedAt: Instant,
    val finishedAt: Instant,
    val result: PortableEvidence? = null,
    val failure: AttemptFailure? = null,
) {
    init {
        require(number > 0 && inputRevision >= 0 && finishedAt >= startedAt)
        require((result == null) != (failure == null))
    }

    override fun toString(): String = "PortableProcessAttempt(number=$number, ***)"
}

/** Settled write with its original key and monotonic try number; both references are host sealed. */
@Serializable
public data class PortableProcessWrite(
    val key: WriteKey,
    val authority: ProcessAuthority,
    val target: PortableEvidence,
    val result: PortableEvidence?,
    val tries: Int,
    val at: Instant,
) {
    init {
        require(tries in 1 until Int.MAX_VALUE)
    }

    override fun toString(): String = "PortableProcessWrite(tries=$tries, ***)"
}

/**
 * Allowlisted v1 manifest. Idle and ended processes only: live attempts and scheduler event keys are not portable.
 * No grants, decisions, credentials, local paths, native handles, arbitrary ScriptData or raw ResultRef is copied.
 * definitionDigest binds the canonical approved source and workflow declarations, excluding host-local ids;
 * a numeric local harness revision is not a portable fingerprint. Changed logic requires a separate explicit flow.
 * The host verifies immutable bytes against OwnershipCheckpoint.digest before decoding and limits them to 128 KiB.
 * Importing this value grants no ownership or execution authority and must happen while Reconciling.
 */
@Serializable
public data class PortableProcessCheckpoint(
    val identity: GitHubTaskIdentity,
    val definitionDigest: String,
    val process: ProcessId,
    val incarnation: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val inputRevision: Long,
    val subject: SubjectSha?,
    val nextAttempt: Int,
    val nextWait: Int,
    val nextWriteTry: Int,
    val history: List<PortableProcessAttempt>,
    val writes: List<PortableProcessWrite>,
    val github: List<PortableGitHubTombstone>,
    val end: ProcessEnd? = null,
    val endedAt: Instant? = null,
    val version: Int = 1,
) {
    init {
        require(version == 1 && isPlainToken(incarnation) && isPortableDigest(definitionDigest))
        require(inputRevision >= 0 && nextAttempt > 0 && nextWait > 0 && nextWriteTry > 0)
        require(updatedAt >= createdAt && (end == null) == (endedAt == null))
        require(endedAt == null || endedAt in createdAt..updatedAt)
        require(history.size <= ProcessLimits.HISTORY && writes.size <= ProcessLimits.WRITES)
        require(github.size <= PORTABLE_GITHUB_OPERATIONS)
        require(history.zipWithNext().all { (a, b) -> a.number < b.number })
        require(history.all { it.number < nextAttempt && it.inputRevision <= inputRevision })
        require(history.all { it.startedAt >= createdAt && it.finishedAt <= updatedAt })
        require(history.map { it.stage }.distinct().size <= ProcessLimits.STAGES)
        require(writes.map { it.key }.distinct().size == writes.size)
        require(writes.all { it.at in createdAt..updatedAt })
        require(github.map { it.key }.distinct().size == github.size)
        require(github.all { it.inputRevision <= inputRevision })
        require(nextWriteTry == (writes.maxOfOrNull { it.tries } ?: 0) + 1)
    }

    override fun toString(): String = "PortableProcessCheckpoint(version=$version, ***)"
}

internal fun isPortableDigest(value: String): Boolean = Regex("[0-9a-f]{64}").matches(value)
