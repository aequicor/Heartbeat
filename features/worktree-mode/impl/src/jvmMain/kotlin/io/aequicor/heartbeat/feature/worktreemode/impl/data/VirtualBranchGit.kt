package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase

/**
 * Git operations between a virtual sandbox and branches of the real repository. They return the changed record;
 * [DesktopVirtualWorkspaces] serializes them and persists the result. No checkout, index or working file changes.
 */
internal class VirtualBranchGit(private val git: VirtualGitObjects) {
    /**
     * Publishes the current snapshot as one commit on a local branch of the real repository. The branch is created
     * at the sandbox base or fast-forwarded; it is never moved sideways and never touches a checked-out branch,
     * the index or any file of the source checkout. A branch checked out in another worktree is only published:
     * with [isPush] the commit continues the tip this sandbox loaded and goes straight to origin, leaving the
     * local branch of that worktree as it is. [isForce] continues the commit this sandbox loaded for the branch
     * (for example an earlier commit chosen with [checkout]) even when the branch moved past it, so history can be
     * rewritten; the push then replaces the remote branch with a lease on its last fetched state.
     */
    suspend fun commit(
        current: VirtualRecord,
        branch: String,
        message: String,
        isPush: Boolean,
        isForce: Boolean = false,
    ): Committed {
        require(message.isNotBlank() && message.length <= MAX_COMMIT_MESSAGE) { "InvalidCommitMessage" }
        require(!branch.startsWith("-") && branch.length <= MAX_BRANCH_NAME) { "InvalidBranchName" }
        check(git.call(current.source, "check-ref-format", "--branch", branch, allowFailure = true).code == 0) {
            "InvalidBranchName"
        }
        val full = "refs/heads/$branch"
        val isCheckedOut = git.text(current.source, "worktree", "list", "--porcelain").lineSequence()
            .any { it.trim() == "branch $full" }
        check(!isCheckedOut || (isPush && current.branchCommits[branch] != null)) { "SandboxBranchCheckedOut" }
        val snapshot = current.state.snapshot
        val tip = if (isForce) null else continuedTip(current, branch, isCheckedOut)
        val tree = git.text(current.source, "rev-parse", "$snapshot^{tree}")
        val parent = tip ?: current.branchCommits[branch] ?: current.state.baseCommit
        val commit = writeCommit(current, tree, parent, message)
        if (!isCheckedOut) {
            if (isForce) {
                git.call(current.source, "update-ref", full, commit)
            } else {
                git.call(current.source, "update-ref", full, commit, tip.orEmpty())
            }
        }
        val record = current.copy(
            branchCommits = current.branchCommits + (branch to commit),
            branch = branch,
            mergeParent = null,
        )
        return Committed(record, commit, isCheckedOut, isForce)
    }

    /**
     * Pushes a branch commit to origin. Without force a remote that moved sideways fails the push and leaves the
     * local commit in place; a forced push replaces the remote only while it still has its last fetched state.
     */
    suspend fun push(committed: Committed, branch: String, isPush: Boolean) {
        if (!isPush) return
        val source = committed.record.source
        val full = "refs/heads/$branch"
        val local = if (committed.isCheckedOut) committed.commit else full
        if (committed.isForce) {
            val known = git.call(
                source,
                "rev-parse",
                "--verify",
                "--quiet",
                "refs/remotes/origin/$branch",
                allowFailure = true,
            )
            val lease = known.text.trim().takeIf { known.code == 0 }.orEmpty()
            // No `+` in the refspec: it would force the update past the lease.
            git.call(source, "push", "--force-with-lease=$full:$lease", "origin", "$local:$full")
        } else {
            git.call(source, "push", "origin", "$local:$full")
        }
    }

    /**
     * Replaces the sandbox files with the tree of a branch of the real repository, optionally fetching it from
     * origin first, so work that lives on a branch the sandbox was not cut from can be continued. The source
     * checkout, its index and its HEAD are untouched; the branch becomes the parent of the next [commit].
     */
    suspend fun checkout(current: VirtualRecord, branch: String, isFetch: Boolean, at: String? = null): VirtualRecord {
        val head = branchTip(current, branch, isFetch, isRemoteFirst = isFetch)
        // An earlier commit of the branch: the next forced commit rewrites the branch from there.
        val tip = at?.let { earlierCommit(current, branch, it, head) } ?: head
        val tree = git.text(current.source, "rev-parse", "$tip^{tree}")
        val snapshot = git.commit(current.source, tree, current.state.baseCommit)
        git.pin(current.source, current.id, snapshot)
        return current.copy(
            state = current.state.copy(snapshot = snapshot, revision = current.state.revision + 1),
            branchCommits = current.branchCommits + (branch to tip),
            branch = branch,
            mergeParent = null,
        )
    }

    /**
     * Takes [paths] (files or directories) from [from] — a branch or a commit — into the sandbox files, removing
     * entries [from] does not have. Splitting a large commit restores its paths step by step onto an earlier one.
     */
    suspend fun restore(current: VirtualRecord, paths: List<String>, from: String): VirtualRecord {
        require(paths.isNotEmpty() && paths.size <= MAX_RESTORE_PATHS) { "InvalidRestorePaths" }
        paths.forEach { DesktopVirtualWorkspaces.safePath(it) }
        val commit = if (COMMIT_ID.matches(from)) resolveCommit(current, from) else branchTip(current, from, false)
        val snapshot = git.restore(current.source, current.state.snapshot, commit, paths)
        git.pin(current.source, current.id, snapshot)
        return current.copy(state = current.state.copy(snapshot = snapshot, revision = current.state.revision + 1))
    }

    /**
     * [at] must be in the history of the branch as origin, the local branch or this sandbox know it: a local branch
     * another worktree holds may lag behind origin, and the sandbox may have committed past both.
     */
    private suspend fun earlierCommit(record: VirtualRecord, branch: String, at: String, head: String): String {
        val commit = resolveCommit(record, at)
        val tips = listOfNotNull(
            head,
            knownCommit(record, "refs/remotes/origin/$branch"),
            knownCommit(record, "refs/heads/$branch"),
            record.branchCommits[branch],
        ).distinct()
        check(tips.any { isAncestor(record.source, commit, it) }) { "SandboxCommitNotOnBranch" }
        return commit
    }

    private suspend fun knownCommit(record: VirtualRecord, ref: String): String? {
        val found = git.call(record.source, "rev-parse", "--verify", "--quiet", "$ref^{commit}", allowFailure = true)
        return found.text.trim().takeIf { found.code == 0 }
    }

    private suspend fun resolveCommit(record: VirtualRecord, id: String): String {
        require(COMMIT_ID.matches(id)) { "InvalidCommitId" }
        val found = git.call(record.source, "rev-parse", "--verify", "--quiet", "$id^{commit}", allowFailure = true)
        return checkNotNull(found.text.trim().takeIf { found.code == 0 }) { "SandboxCommitUnknown" }
    }

    /**
     * Three-way merges a branch of the real repository (for example the pull request base) into the sandbox
     * files. The merge base is taken against the branch the sandbox continues, so the next [commit]
     * records the merged tip as a second parent. Conflicts keep the sandbox unchanged until the user authorizes
     * their resolution, exactly like a child integration.
     */
    suspend fun merge(current: VirtualRecord, branch: String, isFetch: Boolean): VirtualRecord? {
        val theirs = branchTip(current, branch, isFetch)
        val history = current.branch?.let { current.branchCommits[it] } ?: current.state.baseCommit
        if (isAncestor(current.source, theirs, history) || theirs == current.mergeParent) {
            return null
        }
        val base = git.text(current.source, "merge-base", history, theirs)
        val merged = git.merge(current.source, base, current.state.snapshot, theirs)
        val snapshot = git.commit(current.source, merged.tree, current.state.baseCommit)
        git.pin(current.source, current.id, snapshot)
        return afterMerge(current, branch, theirs, snapshot, merged.conflicts)
    }

    /**
     * The sandbox after merging [theirs]. Merging the continued branch itself catches up with it, so the next commit
     * continues its tip; another branch becomes the next commit's second parent.
     */
    private fun afterMerge(
        current: VirtualRecord,
        branch: String,
        theirs: String,
        snapshot: String,
        conflicts: List<String>,
    ): VirtualRecord {
        val isClean = conflicts.isEmpty()
        val isOwn = branch == current.branch
        return current.copy(
            state = current.state.copy(
                phase = if (isClean) VirtualSandboxPhase.Ready else VirtualSandboxPhase.Conflicted,
                snapshot = if (isClean) snapshot else current.state.snapshot,
                revision = current.state.revision + 1,
                conflicts = conflicts,
            ),
            pendingBase = current.state.baseCommit.takeUnless { isClean },
            pendingSnapshot = snapshot.takeUnless { isClean },
            branchCommits = if (isOwn) current.branchCommits + (branch to theirs) else current.branchCommits,
            mergeParent = if (isOwn) current.mergeParent else theirs,
        )
    }

    /** Read-only history of [branch] since [base] in the real repository; nothing is checked out or written. */
    suspend fun history(current: VirtualRecord, branch: String, base: String, isStat: Boolean): String {
        val head = branchTip(current, branch, isFetch = false)
        val from = branchTip(current, base, isFetch = false)
        val commits = git.text(
            current.source,
            "log",
            "--reverse",
            "--max-count=$MAX_HISTORY_COMMITS",
            "--format=%H %an <%ae>%n  %s%n%b",
            "$from..$head",
        )
        val stat = if (isStat) git.text(current.source, "diff", "--stat=160", "$from...$head") else ""
        return (commits + if (stat.isEmpty()) "" else "\n\n$stat").take(MAX_HISTORY_CHARS)
    }

    /** A fetched branch resolves to its origin state first; otherwise a local branch with unpushed work wins. */
    private suspend fun branchTip(
        record: VirtualRecord,
        branch: String,
        isFetch: Boolean,
        isRemoteFirst: Boolean = true,
    ): String {
        require(!branch.startsWith("-") && branch.length <= MAX_BRANCH_NAME) { "InvalidBranchName" }
        check(git.call(record.source, "check-ref-format", "--branch", branch, allowFailure = true).code == 0) {
            "InvalidBranchName"
        }
        val remote = "refs/remotes/origin/$branch"
        if (isFetch) git.call(record.source, "fetch", "--no-tags", "origin", "+refs/heads/$branch:$remote")
        val refs = listOf(remote, "refs/heads/$branch")
        val found = (if (isRemoteFirst) refs else refs.reversed()).firstNotNullOfOrNull { full ->
            git.call(record.source, "rev-parse", "--verify", "--quiet", "$full^{commit}", allowFailure = true)
                .takeIf { it.code == 0 }?.text?.trim()
        }
        return checkNotNull(found) { "SandboxBranchUnknown" }
    }

    /**
     * The commit a branch commit continues. A branch this sandbox already committed to advances again; anything
     * else must be an ancestor of the snapshot. A branch checked out elsewhere continues the tip this sandbox loaded.
     */
    private suspend fun continuedTip(record: VirtualRecord, branch: String, isCheckedOut: Boolean): String? {
        if (isCheckedOut) return record.branchCommits[branch]
        val existing = git.call(record.source, "rev-parse", "--verify", "refs/heads/$branch", allowFailure = true)
        val tip = existing.text.trim().takeIf { existing.code == 0 }
        check(
            tip == null || tip == record.branchCommits[branch] || isAncestor(record.source, tip, record.state.snapshot),
        ) { "SandboxBranchDiverged" }
        return tip
    }

    /**
     * Records a pending merged tip as the second parent unless [parent] already contains it. A branch commit is
     * authored by the repository's configured user, like a commit made in the checkout.
     */
    private suspend fun writeCommit(record: VirtualRecord, tree: String, parent: String, message: String): String {
        val source = record.source
        val merged = record.mergeParent?.takeUnless { it == parent || isAncestor(source, it, parent) }
        val author = userIdentity(source)
        return if (merged == null) {
            git.call(source, "commit-tree", "--no-gpg-sign", tree, "-p", parent, "-m", message, environment = author)
        } else {
            git.call(
                source,
                "commit-tree",
                "--no-gpg-sign",
                tree,
                "-p",
                parent,
                "-p",
                merged,
                "-m",
                message,
                environment = author,
            )
        }.text.trim()
    }

    private suspend fun userIdentity(source: String): Map<String, String> {
        val name = git.call(source, "config", "--get", "user.name", allowFailure = true).text.trim()
        val email = git.call(source, "config", "--get", "user.email", allowFailure = true).text.trim()
        return if (name.isEmpty() || email.isEmpty()) {
            emptyMap()
        } else {
            mapOf(
                "GIT_AUTHOR_NAME" to name,
                "GIT_AUTHOR_EMAIL" to email,
                "GIT_COMMITTER_NAME" to name,
                "GIT_COMMITTER_EMAIL" to email,
            )
        }
    }

    private suspend fun isAncestor(source: String, ancestor: String, commit: String): Boolean =
        git.call(source, "merge-base", "--is-ancestor", ancestor, commit, allowFailure = true).code == 0

    /**
     * A sandbox that loaded a branch ([checkout]) holds that branch's files, not the project's: a moved source
     * checkout only re-anchors its snapshot on the new HEAD, and a conflicted rebase from before is dropped.
     */
    suspend fun reanchor(record: VirtualRecord, head: String): VirtualRecord {
        val tree = git.text(record.source, "rev-parse", "${record.state.snapshot}^{tree}")
        val snapshot = git.commit(record.source, tree, head)
        git.pin(record.source, record.id, snapshot)
        return record.copy(
            pendingBase = null,
            pendingSnapshot = null,
            isResolving = false,
            isHistoryChanged = false,
            state = record.state.copy(
                phase = VirtualSandboxPhase.Ready,
                baseCommit = head,
                snapshot = snapshot,
                conflicts = emptyList(),
                isResolving = false,
                deadlineMillis = null,
                revision = record.state.revision + 1,
            ),
        )
    }
}

/** A branch commit: the record to persist, the commit and whether another worktree holds the branch. */
internal data class Committed(
    val record: VirtualRecord,
    val commit: String,
    val isCheckedOut: Boolean,
    val isForce: Boolean = false,
)

private const val MAX_COMMIT_MESSAGE = 8 * 1024
private const val MAX_BRANCH_NAME = 200
private const val MAX_HISTORY_COMMITS = 200
private const val MAX_HISTORY_CHARS = 64 * 1024
private const val MAX_RESTORE_PATHS = 500
private val COMMIT_ID = Regex("[0-9a-f]{7,64}")
