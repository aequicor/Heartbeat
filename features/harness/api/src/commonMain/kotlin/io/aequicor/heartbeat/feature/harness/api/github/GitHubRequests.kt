package io.aequicor.heartbeat.feature.harness.api.github

import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import kotlinx.serialization.Serializable

/** Fixed read routes. Pagination is explicit and bounded to 50 records per request. */
@Serializable
public sealed interface GitHubQuery {
    /** Read one issue. */
    @Serializable
    public data class Issue(val number: Long) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.Issue(***)"
    }

    /** All issues including closed ones, newest updates first; PRs are filtered out. */
    @Serializable
    public data class Issues(val page: Int = 1) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.Issues(***)"
    }

    /** Direct sub-issues of one issue. */
    @Serializable
    public data class SubIssues(val number: Long, val page: Int = 1) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.SubIssues(***)"
    }

    /** Read one pull request. */
    @Serializable
    public data class Pull(val number: Long) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.Pull(***)"
    }

    /** All pull requests including closed ones. */
    @Serializable
    public data class Pulls(val page: Int = 1) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.Pulls(***)"
    }

    /** Conversation comments, including those used to reconcile external writes. */
    @Serializable
    public data class Comments(val number: Long, val page: Int = 1) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.Comments(***)"
    }

    /** Reviews with the exact reviewed commit. */
    @Serializable
    public data class Reviews(val number: Long, val page: Int = 1) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.Reviews(***)"
    }

    /** Runs of one numeric workflow id. */
    @Serializable
    public data class Runs(val workflow: Long, val page: Int = 1) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.Runs(***)"
    }

    /** One exact run. */
    @Serializable
    public data class Run(val id: Long) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.Run(***)"
    }

    /** Metadata of the artifacts belonging to one exact run. */
    @Serializable
    public data class Artifacts(val run: Long, val page: Int = 1) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.Artifacts(***)"
    }

    /** Releases, including drafts visible to the authenticated user. */
    @Serializable
    public data class Releases(val page: Int = 1) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.Releases(***)"
    }

    /** Resolve a branch or commit reference to its commit. */
    @Serializable
    public data class Commit(val ref: String) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.Commit(***)"
    }

    /** Resolve a tag to its peeled commit; required before accepting release evidence. */
    @Serializable
    public data class Tag(val name: String) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.Tag(***)"
    }

    /** GraphQL project membership with the server-issued opaque cursor. */
    @Serializable
    public data class ProjectItems(val project: GitHubNodeId, val after: String? = null) : GitHubQuery {
        override fun toString(): String = "GitHubQuery.ProjectItems(***)"
    }
}

/**
 * Typed writes. The host chooses routes, authority and correlation; no arbitrary API path, method or shell text.
 * Text fields are untrusted payload. A write key must always bind the exact serialized change and input revision.
 */
@Serializable
public sealed interface GitHubChange {
    /** Open an issue; the host appends an opaque operation marker to its body. */
    @Serializable
    public data class CreateIssue(val title: String, val body: String) : GitHubChange {
        override fun toString(): String = "GitHubChange.CreateIssue(***)"
    }

    /** Open a draft PR; the host verifies [head] and appends the operation marker. */
    @Serializable
    public data class CreatePull(
        val title: String,
        val body: String,
        val branch: String,
        val base: String,
        val head: SubjectSha,
    ) : GitHubChange {
        override fun toString(): String = "GitHubChange.CreatePull(***)"
    }

    /** Post a conversation comment with an operation marker. */
    @Serializable
    public data class Comment(val number: Long, val body: String) : GitHubChange {
        override fun toString(): String = "GitHubChange.Comment(***)"
    }

    /** Add existing issue database id as a direct child. */
    @Serializable
    public data class AddSubIssue(val parent: Long, val child: Long) : GitHubChange {
        override fun toString(): String = "GitHubChange.AddSubIssue(***)"
    }

    /** Add existing issue or PR node to a project. */
    @Serializable
    public data class AddProjectItem(val project: GitHubNodeId, val content: GitHubNodeId) : GitHubChange {
        override fun toString(): String = "GitHubChange.AddProjectItem(***)"
    }

    /**
     * Dispatch a branch or tag expecting an exact commit. A moved ref never confirms as the expected run.
     * [correlationInput], if provided, must be declared by the workflow;
     * the host injects its operation marker there. Lost responses remain Unknown unless exact evidence is found.
     */
    @Serializable
    public data class Dispatch(
        val workflow: Long,
        val ref: String,
        val head: SubjectSha,
        val inputs: Map<String, String> = emptyMap(),
        val correlationInput: String? = null,
    ) : GitHubChange {
        override fun toString(): String = "GitHubChange.Dispatch(***)"
    }

    /** Merge only the expected head after independent, host-owned admission. */
    @Serializable
    public data class Merge(val number: Long, val head: SubjectSha) : GitHubChange {
        override fun toString(): String = "GitHubChange.Merge(***)"
    }

    /** Create a draft release after host-owned admission; tag must already resolve to [head]. */
    @Serializable
    public data class CreateRelease(val tag: String, val head: SubjectSha, val body: String) : GitHubChange {
        override fun toString(): String = "GitHubChange.CreateRelease(***)"
    }
}
