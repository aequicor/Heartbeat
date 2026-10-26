package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Git operations between a virtual sandbox and branches of the real repository. They return the changed record;
 * [DesktopVirtualWorkspaces] serializes them and persists the result. No checkout, index or working file changes.
 * Network operations ([fetch], [push]) are separate calls, so the caller runs them outside its record lock.
 */
internal class VirtualBranchGit(private val git: VirtualGitObjects) {
    private val log = Log.tag("VirtualBranchGit")
    private val isIdentityFallbackLogged = AtomicBoolean(false)

    /**
     * Publishes the current snapshot as one commit on a local branch of the real repository. The branch is created
     * at the sandbox base or fast-forwarded; it is never moved sideways and never touches a checked-out branch,
     * the index or any file of the source checkout. A branch checked out in another worktree is only published:
     * with [isPush] the commit continues the tip this sandbox loaded, is pinned under `refs/heartbeat/virtual` and
     * goes straight to origin, leaving the local branch of that worktree as it is. [isForce] continues the commit
     * this sandbox loaded for the branch (for example an earlier commit chosen with [checkout]) even when the branch
     * moved past it, so history can be rewritten; only a branch loaded by [checkout] or committed by this sandbox can
     * be forced, and the local branch moves only from the tip this sandbox observed when it loaded or last committed
     * the branch ([VirtualRecord.localTips]): a local branch moved since then refuses with `SandboxBranchMoved`
     * instead of losing those commits. A retry whose files and message equal this sandbox's own last commit of the
     * branch reuses that commit, so a failed push is retried without a duplicate.
     */
    suspend fun commit(
        current: VirtualRecord,
        branch: String,
        message: String,
        isPush: Boolean,
        isForce: Boolean = false,
    ): Committed {
        require(message.isNotBlank() && message.length <= MAX_COMMIT_MESSAGE) { "InvalidCommitMessage" }
        validateBranch(current, branch)
        val full = "refs/heads/$branch"
        val isCheckedOut = git.text(current.source, "worktree", "list", "--porcelain").lineSequence()
            .any { it.trim() == "branch $full" }
        val loaded = current.branchCommits[branch]
        check(!isCheckedOut || (isPush && loaded != null)) { "SandboxBranchCheckedOut" }
        check(!isForce || loaded != null) { "SandboxBranchNotLoaded" }
        val local = if (isCheckedOut) null else knownCommit(current, full)
        val parent = when {
            isCheckedOut -> checkNotNull(loaded)
            isForce -> forcedParent(current, branch, local, checkNotNull(loaded))
            else -> continuedTip(current, local, loaded)
        }
        val tree = git.text(current.source, "rev-parse", "${current.state.snapshot}^{tree}")
        val commit = if (isRetry(current, branch, parent, tree, message)) {
            parent
        } else {
            writeCommit(current, tree, parent, message)
        }
        if (isCheckedOut) {
            // No branch holds it: the pin keeps the commit reachable until the push publishes it.
            git.pin(current.source, current.id, commit)
        } else if (commit != local) {
            git.call(current.source, "update-ref", full, commit, local.orEmpty())
        }
        val record = current.copy(
            branchCommits = current.branchCommits + (branch to commit),
            ownCommits = current.ownCommits + (branch to commit),
            localTips = if (isCheckedOut) current.localTips else current.localTips + (branch to commit),
            branch = branch,
            mergeParent = null,
        )
        return Committed(record, commit, isCheckedOut, isForce, current.origins[branch])
    }

    /**
     * A forced commit continues [loaded] and moves the local branch only from the tip this sandbox observed; old
     * records have no observed tip, so the loaded commit is the best known state of the branch.
     */
    private fun forcedParent(current: VirtualRecord, branch: String, local: String?, loaded: String): String {
        check(local == null || local == (current.localTips[branch] ?: loaded)) { "SandboxBranchMoved" }
        return loaded
    }

    /** [parent] is this sandbox's own last commit of [branch] with the same files and message. */
    private suspend fun isRetry(
        current: VirtualRecord,
        branch: String,
        parent: String,
        tree: String,
        message: String,
    ): Boolean = parent == current.ownCommits[branch] && current.mergeParent == null &&
        git.text(current.source, "rev-parse", "$parent^{tree}") == tree &&
        git.text(current.source, "show", "-s", "--format=%B", parent) == message.trim()

    /**
     * Pushes a branch commit to origin; a network operation. Without force a remote that moved sideways fails the
     * push and leaves the local commit in place; a forced push replaces the remote only while it still has the
     * state this sandbox observed when it loaded or merged the branch.
     */
    suspend fun push(committed: Committed, branch: String) {
        val source = committed.record.source
        val full = "refs/heads/$branch"
        if (committed.isForce) {
            // No `+` in the refspec: it would force the update past the lease.
            git.call(
                source,
                "push",
                "--force-with-lease=$full:${committed.lease.orEmpty()}",
                "origin",
                "${committed.commit}:$full",
            )
        } else {
            git.call(source, "push", "origin", "${committed.commit}:$full")
        }
    }

    /** Updates `refs/remotes/origin/<branch>` from origin; a network operation that changes no sandbox state. */
    suspend fun fetch(record: VirtualRecord, branch: String) {
        validateBranch(record, branch)
        git.call(record.source, "fetch", "--no-tags", "origin", "+refs/heads/$branch:refs/remotes/origin/$branch")
    }

    /**
     * Replaces the sandbox files with the tree of a branch of the real repository, so work that lives on a branch
     * the sandbox was not cut from can be continued. After a [fetch] ([isFetched]) the origin state wins over a
     * stale local branch. The source checkout, its index and its HEAD are untouched; the branch becomes the parent
     * of the next [commit]. The local tip seen now guards a later forced [commit] only when the loaded tip contains
     * it; a local branch with commits the loaded tip lacks (origin won after a fetch) is recorded as unseen, so a
     * forced commit refuses with `SandboxBranchMoved` instead of dropping them. The origin state becomes the lease
     * of a later forced push only when it is part of what was loaded (fetched now, or contained in the tip).
     */
    suspend fun checkout(
        current: VirtualRecord,
        branch: String,
        isFetched: Boolean,
        at: String? = null,
    ): VirtualRecord {
        val head = branchTip(current, branch, isRemoteFirst = isFetched)
        // An earlier commit of the branch: the next forced commit rewrites the branch from there.
        val tip = at?.let { earlierCommit(current, branch, it, head) } ?: head
        val tree = git.text(current.source, "rev-parse", "$tip^{tree}")
        val snapshot = git.commit(current.source, tree, current.state.baseCommit)
        git.pin(current.source, current.id, snapshot)
        val origin = observedOrigin(current, branch, head, isFetched)
        val local = knownCommit(current, "refs/heads/$branch")
            ?.let { if (isAncestor(current.source, it, head)) it else UNSEEN_LOCAL_TIP }
        return current.copy(
            state = current.state.copy(snapshot = snapshot, revision = current.state.revision + 1),
            branchCommits = current.branchCommits + (branch to tip),
            localTips = if (local == null) current.localTips - branch else current.localTips + (branch to local),
            branch = branch,
            mergeParent = null,
            origins = if (origin == null) current.origins - branch else current.origins + (branch to origin),
        )
    }

    /**
     * Takes [paths] (files or directories) from [from] — a branch or a commit — into the sandbox files, removing
     * entries [from] does not have. A name that is a branch is read as that branch even when it looks like a commit
     * id. Splitting a large commit restores its paths step by step onto an earlier one.
     */
    suspend fun restore(current: VirtualRecord, paths: List<String>, from: String): VirtualRecord {
        require(paths.isNotEmpty() && paths.size <= MAX_RESTORE_PATHS) { "InvalidRestorePaths" }
        val normalized = paths.map { DesktopVirtualWorkspaces.safePath(it.trimEnd('/')) }
        val isBranchName = !from.startsWith("-") && from.length <= MAX_BRANCH_NAME &&
            git.call(current.source, "check-ref-format", "--branch", from, allowFailure = true).code == 0
        val branchCommit = if (isBranchName) findBranch(current, from, isRemoteFirst = false) else null
        val commit = when {
            branchCommit != null -> branchCommit
            COMMIT_ID.matches(from) -> resolveCommit(current, from)
            isBranchName -> error("SandboxBranchUnknown")
            else -> throw IllegalArgumentException("InvalidBranchName")
        }
        val snapshot = git.restore(current.source, current.state.snapshot, commit, normalized)
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

    /**
     * The origin state a forced push may lease on: only one this sandbox has seen. A remote-tracking ref that a
     * background fetch moved past the loaded [tip] holds commits the sandbox never saw, so it is no lease.
     */
    private suspend fun observedOrigin(
        record: VirtualRecord,
        branch: String,
        tip: String,
        isFetched: Boolean,
    ): String? {
        val origin = knownCommit(record, "refs/remotes/origin/$branch") ?: return null
        return origin.takeIf { isFetched || isAncestor(record.source, it, tip) }
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
     * their resolution, exactly like a child integration; the merge is recorded for the next commit only once
     * they are resolved. After a [fetch] ([isFetched]) the origin state of [branch] is merged, otherwise its local
     * state wins. Merging the continued branch records the local tip it takes in (equal to or contained in the
     * merged tip) as observed, so a forced commit refused with `SandboxBranchMoved` succeeds after the merge; null
     * when nothing changes.
     */
    suspend fun merge(current: VirtualRecord, branch: String, isFetched: Boolean): VirtualRecord? {
        val theirs = branchTip(current, branch, isRemoteFirst = isFetched)
        val history = current.branch?.let { current.branchCommits[it] } ?: current.state.baseCommit
        val isOwn = branch == current.branch
        val local = if (isOwn) knownCommit(current, "refs/heads/$branch") else null
        if (isAncestor(current.source, theirs, history) || theirs == current.mergeParent) {
            // Nothing to merge; a local tip the continued commit already contains is observed from now on.
            val seen = local?.takeIf { it != current.localTips[branch] && isAncestor(current.source, it, history) }
            return seen?.let { current.copy(localTips = current.localTips + (branch to it)) }
        }
        val base = git.text(current.source, "merge-base", history, theirs)
        val merged = git.merge(current.source, base, current.state.snapshot, theirs)
        val snapshot = git.commit(current.source, merged.tree, current.state.baseCommit)
        git.pin(current.source, current.id, snapshot)
        val isCatchUp = isOwn && !keepsLocalTip(current, branch, history, theirs)
        val pending = PendingMerge(
            branch,
            theirs,
            isCatchUp = isCatchUp,
            origin = if (isOwn) observedOrigin(current, branch, theirs, isFetched) else null,
            localTip = local?.takeIf { isCatchUp && isAncestor(current.source, it, theirs) },
        )
        return afterMerge(current, pending, snapshot, merged.conflicts)
    }

    /**
     * Merging the continued branch itself catches up with it, so the next commit continues its tip. When the local
     * branch is not checked out elsewhere and still holds the commit this sandbox continues, which origin does not
     * contain (an unpushed commit after a rejected push), that commit stays the parent and the merged tip becomes the
     * second one: the next commit then fast-forwards both the local branch and origin.
     */
    private suspend fun keepsLocalTip(record: VirtualRecord, branch: String, history: String, theirs: String): Boolean {
        val full = "refs/heads/$branch"
        val isCheckedOut = git.text(record.source, "worktree", "list", "--porcelain").lineSequence()
            .any { it.trim() == "branch $full" }
        return !isCheckedOut && knownCommit(record, full) == history && !isAncestor(record.source, history, theirs)
    }

    /** The sandbox after a merge; a conflicted one keeps [PendingMerge] until [withMerge] promotes it. */
    private fun afterMerge(
        current: VirtualRecord,
        pending: PendingMerge,
        snapshot: String,
        conflicts: List<String>,
    ): VirtualRecord {
        val isClean = conflicts.isEmpty()
        val next = current.copy(
            state = current.state.copy(
                phase = if (isClean) VirtualSandboxPhase.Ready else VirtualSandboxPhase.Conflicted,
                snapshot = if (isClean) snapshot else current.state.snapshot,
                revision = current.state.revision + 1,
                conflicts = conflicts,
            ),
            pendingBase = current.state.baseCommit.takeUnless { isClean },
            pendingSnapshot = snapshot.takeUnless { isClean },
            pending = VirtualPending.Merge.takeUnless { isClean },
            pendingMerge = pending.takeUnless { isClean },
        )
        return if (isClean) next.withMerge(pending) else next
    }

    /** Read-only history of [branch] since [base] in the real repository; local branches win over origin. */
    suspend fun history(current: VirtualRecord, branch: String, base: String, isStat: Boolean): String {
        val head = branchTip(current, branch, isRemoteFirst = false)
        val from = branchTip(current, base, isRemoteFirst = false)
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
    private suspend fun branchTip(record: VirtualRecord, branch: String, isRemoteFirst: Boolean): String {
        validateBranch(record, branch)
        return checkNotNull(findBranch(record, branch, isRemoteFirst)) { "SandboxBranchUnknown" }
    }

    private suspend fun findBranch(record: VirtualRecord, branch: String, isRemoteFirst: Boolean): String? {
        val refs = listOf("refs/remotes/origin/$branch", "refs/heads/$branch")
        return (if (isRemoteFirst) refs else refs.reversed()).firstNotNullOfOrNull { knownCommit(record, it) }
    }

    private suspend fun validateBranch(record: VirtualRecord, branch: String) {
        require(!branch.startsWith("-") && branch.length <= MAX_BRANCH_NAME) { "InvalidBranchName" }
        check(git.call(record.source, "check-ref-format", "--branch", branch, allowFailure = true).code == 0) {
            "InvalidBranchName"
        }
    }

    /**
     * The commit a branch commit continues. The local [tip] advances when this sandbox continues it (or a later
     * commit of it, such as a caught-up origin tip — then [loaded]) or when it is an ancestor of the snapshot;
     * anything else moved independently.
     */
    private suspend fun continuedTip(record: VirtualRecord, tip: String?, loaded: String?): String {
        if (tip == null) return loaded ?: record.state.baseCommit
        if (loaded != null && isAncestor(record.source, tip, loaded)) return loaded
        check(isAncestor(record.source, tip, record.state.snapshot)) { "SandboxBranchDiverged" }
        return tip
    }

    /**
     * Records a pending merged tip as the second parent unless [parent] already contains it. A branch commit is
     * authored by the repository's configured user, like a commit made in the checkout.
     */
    private suspend fun writeCommit(record: VirtualRecord, tree: String, parent: String, message: String): String {
        val source = record.source
        val merged = record.mergeParent?.takeUnless { it == parent || isAncestor(source, it, parent) }
        val parents = listOfNotNull(parent, merged).flatMap { listOf("-p", it) }
        return git.call(
            source,
            "commit-tree",
            "--no-gpg-sign",
            tree,
            *parents.toTypedArray(),
            "-m",
            message,
            environment = userIdentity(source),
        ).text.trim()
    }

    private suspend fun userIdentity(source: String): Map<String, String> {
        val name = git.call(source, "config", "--get", "user.name", allowFailure = true).text.trim()
        val email = git.call(source, "config", "--get", "user.email", allowFailure = true).text.trim()
        return if (name.isEmpty() || email.isEmpty()) {
            if (isIdentityFallbackLogged.compareAndSet(false, true)) {
                log.d { "Repository user is not configured; sandbox branch commits use the sandbox identity" }
            }
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
     * Whether a sandbox with a loaded branch must [reanchor]: the source moved, its history changed, or it holds a
     * conflicted rebase from before the branch was loaded. A merge or child integration conflict is not stale.
     */
    fun isStale(record: VirtualRecord, head: String): Boolean =
        head != record.state.baseCommit || record.isHistoryChanged ||
            (record.pendingSnapshot != null && (!record.keepsConflict || record.pendingBase != head))

    /**
     * A sandbox that loaded a branch ([checkout]) holds that branch's files, not the project's: a moved source
     * checkout only re-anchors its snapshot on the new HEAD. A conflicted rebase from before is dropped, while a
     * merge or child integration conflict is kept (with its resolution authorization) and re-anchored too.
     */
    suspend fun reanchor(record: VirtualRecord, head: String): VirtualRecord {
        val snapshot = anchored(record, record.state.snapshot, head)
        if (record.keepsConflict) {
            return record.copy(
                pendingBase = head,
                pendingSnapshot = anchored(record, checkNotNull(record.pendingSnapshot), head),
                isHistoryChanged = false,
                state = record.state.copy(
                    phase = VirtualSandboxPhase.Conflicted,
                    baseCommit = head,
                    snapshot = snapshot,
                    deadlineMillis = null,
                    revision = record.state.revision + 1,
                ),
            )
        }
        return record.copy(
            pendingBase = null,
            pendingSnapshot = null,
            pending = null,
            pendingMerge = null,
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

    private suspend fun anchored(record: VirtualRecord, commit: String, head: String): String {
        val tree = git.text(record.source, "rev-parse", "$commit^{tree}")
        return git.commit(record.source, tree, head).also { git.pin(record.source, record.id, it) }
    }
}

/**
 * A branch commit: the record to persist, the commit, whether another worktree holds the branch and the origin
 * state a forced push leases on.
 */
internal data class Committed(
    val record: VirtualRecord,
    val commit: String,
    val isCheckedOut: Boolean,
    val isForce: Boolean = false,
    val lease: String? = null,
)

/**
 * Applies a resolved or clean branch merge: a caught-up tip (with the local tip it took in), or the second parent of
 * the next commit.
 */
internal fun VirtualRecord.withMerge(merge: PendingMerge): VirtualRecord = copy(
    branchCommits = if (merge.isCatchUp) branchCommits + (merge.branch to merge.theirs) else branchCommits,
    mergeParent = if (merge.isCatchUp) mergeParent else merge.theirs,
    origins = merge.origin?.let { origins + (merge.branch to it) } ?: origins,
    localTips = merge.localTip?.let { localTips + (merge.branch to it) } ?: localTips,
)

/** A [VirtualRecord.localTips] entry no local tip equals: the loaded tip did not contain the local branch. */
private const val UNSEEN_LOCAL_TIP = "unseen"

private const val MAX_COMMIT_MESSAGE = 8 * 1024
private const val MAX_BRANCH_NAME = 200
private const val MAX_HISTORY_COMMITS = 200
private const val MAX_HISTORY_CHARS = 64 * 1024
private const val MAX_RESTORE_PATHS = 500
private val COMMIT_ID = Regex("[0-9a-f]{7,64}")
