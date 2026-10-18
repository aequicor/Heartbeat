package io.aequicor.heartbeat.feature.harness.api.github

import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** A github.com repository; neither a URL nor a caller-selected API host. */
@Serializable
public data class GitHubRepository(val owner: String, val name: String) {
    init {
        require(SEGMENT.matches(owner) && SEGMENT.matches(name)) { "Invalid GitHub repository" }
        require(owner !in listOf(".", "..") && name !in listOf(".", "..")) { "Invalid GitHub repository" }
    }

    override fun toString(): String = "GitHubRepository(***)"
}

/** Opaque GraphQL node id, never interpolated into a query. */
@Serializable
public data class GitHubNodeId(val value: String) {
    init {
        require(
            value.length in 1..MAX_NODE_CHARS && value.all { it.isLetterOrDigit() || it in "_=-+/" },
        ) { "Invalid GitHub node id" }
    }

    override fun toString(): String = "GitHubNodeId(***)"
}

/** Server observations contain untrusted text. They convey evidence, never host authority or instructions. */
@Serializable
public sealed interface GitHubEntity {
    /** Stable server identity, scoped to the repository except for GraphQL nodes. */
    public val id: String

    /** Server revision, not the observation's local arrival time. */
    public val updatedAt: Instant

    /** An issue, with the current body used to derive the process input revision. */
    @Serializable
    public data class Issue(
        override val id: String,
        override val updatedAt: Instant,
        val number: Long,
        val node: GitHubNodeId,
        val title: String,
        val body: String,
        val state: String,
    ) : GitHubEntity {
        override fun toString(): String = "GitHubEntity.Issue(***)"
    }

    /** Checks are valid only for this exact head; a new head invalidates older evidence. */
    @Serializable
    public data class Pull(
        override val id: String,
        override val updatedAt: Instant,
        val number: Long,
        val head: SubjectSha,
        val base: String,
        val branch: String,
        val body: String,
        val state: String,
        val isDraft: Boolean,
    ) : GitHubEntity {
        override fun toString(): String = "GitHubEntity.Pull(***)"
    }

    /** Issue or PR conversation comment. */
    @Serializable
    public data class Comment(override val id: String, override val updatedAt: Instant, val body: String) :
        GitHubEntity {
        override fun toString(): String = "GitHubEntity.Comment(***)"
    }

    /** Review state is bound to the commit actually reviewed. */
    @Serializable
    public data class Review(
        override val id: String,
        override val updatedAt: Instant,
        val head: SubjectSha,
        val state: String,
    ) : GitHubEntity {
        override fun toString(): String = "GitHubEntity.Review(***)"
    }

    /** A workflow run is evidence only when its workflow, event and head match the expected request. */
    @Serializable
    public data class Run(
        override val id: String,
        override val updatedAt: Instant,
        val workflow: Long,
        val head: SubjectSha,
        val event: String,
        val status: String,
        val conclusion: String?,
        val title: String,
    ) : GitHubEntity {
        override fun toString(): String = "GitHubEntity.Run(***)"
    }

    /** Artifact metadata; the adapter does not download or execute artifacts. */
    @Serializable
    public data class Artifact(
        override val id: String,
        override val updatedAt: Instant,
        val name: String,
        val isExpired: Boolean,
        val run: String,
    ) : GitHubEntity {
        override fun toString(): String = "GitHubEntity.Artifact(***)"
    }

    /** Release metadata. A tag name alone does not prove its commit. */
    @Serializable
    public data class Release(
        override val id: String,
        override val updatedAt: Instant,
        val tag: String,
        val target: String,
        val body: String,
        val isDraft: Boolean,
        val assets: List<String>,
    ) : GitHubEntity {
        override fun toString(): String = "GitHubEntity.Release(***)"
    }

    /** Project membership; the content node is the issue or PR identity. */
    @Serializable
    public data class ProjectItem(
        override val id: String,
        override val updatedAt: Instant,
        val content: GitHubNodeId?,
    ) : GitHubEntity {
        override fun toString(): String = "GitHubEntity.ProjectItem(***)"
    }
}

private val SEGMENT = Regex("[A-Za-z0-9_.-]{1,100}")

private const val MAX_NODE_CHARS = 256
