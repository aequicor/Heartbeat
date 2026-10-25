package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualFileChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspaceProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/** Persisted Git-tree overlays; the source working directory and index are never written. */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<VirtualSandboxStore>())
@ContributesIntoSet(ProfileScope::class, binding = binding<VirtualWorkspaceProvider>())
internal class DesktopVirtualWorkspaces(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val git: VirtualGitObjects,
    private val settings: ProjectBuildSettings,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock = Clock.System,
    private val tasks: Lazy<WorktreeJournal>? = null,
) : VirtualSandboxStore {
    private val store = stores.keyValue(KeyValueSpec("virtual_sandboxes", readPolicy = KeyValueReadPolicy.Strict))
    private val processes = Path.of(stores.filesDirectory("virtual_sandboxes"))
    private val lock = Mutex()
    private val log = Log.tag("VirtualSandboxes")
    private var cached: Map<String, VirtualRecord>? = null
    private val textCache = LinkedHashMap<String, String>()
    private var cachedChars = 0L
    private val unavailable = mutableSetOf<String>()
    private val branches = VirtualBranchGit(git)

    override suspend fun find(ref: WorkspaceRef): VirtualWorkspace? = lock.withLock {
        read()[ref.value]?.let { Handle(ref, processes.resolve(it.id).toString()) }
    }

    override suspend fun create(record: WorktreeRecord): WorkspaceRef = lock.withLock {
        val ref = workspace(record)
        if (ref.value in read()) return@withLock ref
        requireClean(record.sourceDirectory)
        val base = git.text(record.sourceDirectory, "rev-parse", "HEAD")
        check(base == record.task.baseCommit) { "SandboxSourceChanged" }
        val parent = record.parentWorkspace?.let { read()[it.value] }
        check(record.parentWorkspace == null || parent != null) { "SandboxParentUnavailable" }
        if (parent != null) {
            check(
                parent.state.isReady && parent.state.baseCommit == base,
            ) { "SandboxParentUnavailable" }
        }
        val view = parent?.state?.snapshot ?: git.commit(
            record.sourceDirectory,
            git.text(record.sourceDirectory, "rev-parse", "$base^{tree}"),
            base,
        )
        git.pin(record.sourceDirectory, record.provisioning, view)
        withContext(dispatchers.io) { Files.createDirectories(processes.resolve(record.provisioning)) }
        val reflogPath = git.text(
            record.sourceDirectory,
            "rev-parse",
            "--path-format=absolute",
            "--git-path",
            "logs/HEAD",
        )
        save(
            VirtualRecord(
                ref.value,
                record.provisioning,
                record.sourceDirectory,
                record.commonDirectory,
                VirtualSandboxState(baseCommit = base, snapshot = view),
                parent = record.parentWorkspace?.value,
                fork = view,
                reflogPath = reflogPath, reflogStamp = reflogStamp(reflogPath),
                reflogCursor = withContext(dispatchers.io) { advanceReflog(reflogPath, null).cursor },
                // A child continues the branch its parent loaded, so it can commit to it too.
                branchCommits = parent?.branchCommits.orEmpty(),
                localTips = parent?.localTips.orEmpty(),
                branch = parent?.branch,
            ),
        )
        settings.setPoolSize(record.commonDirectory, settings.get(record.commonDirectory).poolSize)
        log.i { "Virtual sandbox created" }
        ref
    }

    override suspend fun state(ref: WorkspaceRef): VirtualSandboxState = lock.withLock { known(ref).state }

    /** Detects source changes first; resumption is a separate operation after confirmed turn/worker stops. */
    override suspend fun inspect(ref: WorkspaceRef): VirtualSandboxState = lock.withLock {
        val record = history(known(ref))
        if (record.state.phase == VirtualSandboxPhase.HandedOff) return@withLock record.state
        detached(record)?.let { return@withLock it }
        val expected = record.pendingBase ?: record.state.baseCommit
        val isChanged = record.isHistoryChanged || !sourceCleanRecord(
            record,
        ) || git.text(record.source, "rev-parse", "HEAD") != expected
        val isPauseAllowed =
            record.state.isReady || record.isResolving || record.state.phase == VirtualSandboxPhase.Conflicted
        if (isChanged && isPauseAllowed) {
            pauseRecord(record).state
        } else {
            save(record).state
        }
    }

    override suspend fun pause(ref: WorkspaceRef): VirtualSandboxState = lock.withLock {
        val record = history(known(ref))
        when {
            record.state.phase != VirtualSandboxPhase.HandedOff && record.branch != null ->
                detached(record) ?: record.state

            record.state.isReady || record.isResolving -> pauseRecord(record).state

            else -> record.state
        }
    }

    /**
     * A loaded branch's files never follow the source, so it never pauses for it: a clean source re-anchors a stale
     * or paused one in place, keeping a merge or child integration conflict. Null when the record has no loaded
     * branch or its source is dirty.
     */
    private suspend fun detached(record: VirtualRecord): VirtualSandboxState? {
        if (record.branch == null || !sourceCleanRecord(record)) return null
        return save(anchored(record, git.text(record.source, "rev-parse", "HEAD"))).state
    }

    /** The record of a loaded branch re-anchored on [head], unchanged when it is neither stale nor paused. */
    private suspend fun anchored(record: VirtualRecord, head: String): VirtualRecord {
        val isSettled = record.state.isReady ||
            (record.keepsConflict && record.state.phase == VirtualSandboxPhase.Conflicted)
        if (!branches.isStale(record, head) && isSettled) return record
        log.i { "Virtual sandbox with a loaded branch re-anchored on the source" }
        return branches.reanchor(record, head)
    }

    override suspend fun sourceClean(ref: WorkspaceRef): Boolean = lock.withLock { sourceCleanRecord(known(ref)) }

    private suspend fun sourceCleanRecord(record: VirtualRecord): Boolean = try {
        val isSourceClean = isClean(record.source) && git.text(record.source, "rev-parse", "HEAD").isNotEmpty()
        unavailable.remove(record.ref)
        isSourceClean
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        if (unavailable.add(
                record.ref,
            )
        ) {
            log.w(IllegalStateException("SandboxSourceUnavailable (${error::class.simpleName.orEmpty()})")) {
                "Virtual sandbox source is unavailable"
            }
        }
        false
    }

    private suspend fun pauseRecord(record: VirtualRecord): VirtualRecord {
        val next = record.state.copy(
            phase = VirtualSandboxPhase.Pausing,
            deadlineMillis =
                record.state.deadlineMillis ?: (clock.now().toEpochMilliseconds() + settings.pauseTimeout()),
            isResolving = false,
            revision = record.state.revision + 1,
        )
        log.i { "Virtual sandbox paused for external source changes" }
        return save(record.copy(state = next, isResolving = false))
    }

    override suspend fun resume(ref: WorkspaceRef, explicit: Boolean, resolveConflicts: Boolean): VirtualSandboxState =
        lock.withLock {
            val record = history(known(ref))
            // Source history never reaches the files of a loaded branch, so it needs no manual continuation.
            val blocked = resumeBarrier(record, explicit || record.branch != null)
            val result = if (blocked != null) {
                blocked
            } else {
                val head = git.text(record.source, "rev-parse", "HEAD")
                when {
                    record.branch != null -> resumeLoaded(record, head, explicit && resolveConflicts)
                    record.pendingSnapshot != null -> resumeConflicts(record, head, explicit, resolveConflicts)
                    else -> rebase(record, head, explicit)
                }
            }
            save(result).state
        }

    /**
     * A kept merge conflict of a loaded branch continues like any other: resolution needs authorization ([isGranted]).
     * Only a re-anchor revokes a granted one; a barrier-wide resume of an unchanged sandbox keeps it.
     */
    private suspend fun resumeLoaded(record: VirtualRecord, head: String, isGranted: Boolean): VirtualRecord {
        val current = anchored(record, head)
        if (!current.keepsConflict) return current
        val isResolving = isGranted || (current == record && record.isResolving)
        return current.copy(
            isResolving = isResolving,
            state = current.state.copy(phase = VirtualSandboxPhase.Conflicted, isResolving = isResolving),
        )
    }

    private suspend fun resumeBarrier(record: VirtualRecord, explicit: Boolean): VirtualRecord? {
        val isExpired = record.state.deadlineMillis?.let { clock.now().toEpochMilliseconds() >= it } == true
        val isClean = sourceCleanRecord(record)
        val hasUntrackedHistory = isClean && record.reflogCursor == null &&
            git.text(record.source, "rev-parse", "HEAD") != record.state.baseCommit
        val isManual = record.state.phase == VirtualSandboxPhase.Manual || record.isHistoryChanged ||
            isExpired || hasUntrackedHistory
        return when {
            record.state.phase == VirtualSandboxPhase.HandedOff -> record
            !explicit && isManual -> record.copy(state = record.state.copy(phase = VirtualSandboxPhase.Manual))
            !isClean -> record.copy(state = record.state.copy(phase = VirtualSandboxPhase.Waiting))
            else -> null
        }
    }

    private suspend fun descendant(record: VirtualRecord, base: String, head: String): Boolean =
        base == head || git.call(
            record.source,
            "merge-base",
            "--is-ancestor",
            base,
            head,
            allowFailure = true,
        ).code == 0

    private suspend fun resumeConflicts(
        record: VirtualRecord,
        head: String,
        explicit: Boolean,
        resolveConflicts: Boolean,
    ): VirtualRecord {
        val base = checkNotNull(record.pendingBase)
        if (!explicit && !descendant(
                record,
                base,
                head,
            )
        ) {
            return record.copy(state = record.state.copy(phase = VirtualSandboxPhase.Manual))
        }
        var current = rebaseFork(record, head)
        if (head != base) {
            val merged = git.merge(record.source, base, head, checkNotNull(record.pendingSnapshot))
            val snapshot = git.commit(record.source, merged.tree, head)
            git.pin(record.source, record.id, snapshot)
            current = current.copy(
                pendingBase = head,
                pendingSnapshot = snapshot,
                pending = current.pending ?: VirtualPending.Rebase,
                state = current.state.copy(
                    revision = current.state.revision + 1,
                    conflicts = (current.state.conflicts + merged.conflicts).distinct(),
                ),
            )
        }
        val isResolving = explicit && resolveConflicts
        return current.copy(
            isResolving = isResolving,
            isHistoryChanged = false,
            state = current.state.copy(phase = VirtualSandboxPhase.Conflicted, isResolving = isResolving),
        )
    }

    private suspend fun rebase(record: VirtualRecord, head: String, explicit: Boolean): VirtualRecord {
        if (!explicit && !descendant(record, record.state.baseCommit, head)) {
            return record.copy(state = record.state.copy(phase = VirtualSandboxPhase.Manual))
        }
        val current = rebaseFork(record, head)
        val rebased = if (head == record.state.baseCommit) current else mergeBase(current, head)
        return if (rebased.pendingSnapshot != null) {
            rebased
        } else {
            rebased.copy(
                isHistoryChanged = false,
                state = rebased.state.copy(phase = VirtualSandboxPhase.Ready, deadlineMillis = null),
            )
        }
    }

    private suspend fun mergeBase(record: VirtualRecord, head: String): VirtualRecord {
        val merged = git.merge(record.source, record.state.baseCommit, head, record.state.snapshot)
        val snapshot = git.commit(record.source, merged.tree, head)
        git.pin(record.source, record.id, snapshot)
        log.i { "Virtual sandbox rebased" }
        return if (merged.conflicts.isNotEmpty()) {
            record.copy(
                pendingBase = head,
                pendingSnapshot = snapshot,
                pending = VirtualPending.Rebase,
                state = record.state.copy(
                    phase = VirtualSandboxPhase.Conflicted,
                    conflicts = merged.conflicts,
                    revision = record.state.revision + 1,
                ),
            )
        } else {
            record.copy(
                state = record.state.copy(baseCommit = head, snapshot = snapshot, revision = record.state.revision + 1),
            )
        }
    }

    private suspend fun rebaseFork(record: VirtualRecord, head: String): VirtualRecord {
        val previous = record.forkBase ?: record.state.baseCommit
        if (previous == head || record.parent == null) return record
        val merged = git.merge(record.source, previous, head, record.fork)
        val fork = git.commit(record.source, merged.tree, head)
        git.pin(record.source, record.id, fork)
        return record.copy(fork = fork, forkBase = head)
    }

    override suspend fun snapshot(ref: WorkspaceRef): String = lock.withLock {
        val record = accessible(ref)
        record.state.snapshot
    }

    override suspend fun handoff(record: WorktreeRecord): VirtualSandboxState = lock.withLock {
        val previous = known(workspace(record))
        if (previous.state.phase == VirtualSandboxPhase.HandedOff) return@withLock previous.state
        val current = accessible(workspace(record))
        check(current.state.isReady) { "SandboxPaused" }
        requireClean(current.source)
        check(git.text(current.source, "rev-parse", "HEAD") == current.state.baseCommit) { "SandboxSourceChanged" }
        val branch = checkNotNull(record.task.branch)
        val snapshot = current.state.snapshot
        val existing = git.call(current.source, "rev-parse", "--verify", "refs/heads/$branch", allowFailure = true)
        val tip = existing.text.trim().takeIf { existing.code == 0 }
        // A reopened sandbox moves only the result branch it published itself.
        check(tip == null || tip == snapshot || tip == current.handedOff) { "SandboxBranchOccupied" }
        // Moving a branch checked out in another worktree would leave its files behind the new result: the pinned
        // snapshot is the result then, and the branch stays where it is.
        val isCheckedOut = tip != null && tip != snapshot &&
            git.text(current.source, "worktree", "list", "--porcelain").lineSequence()
                .any { it.trim() == "branch refs/heads/$branch" }
        if (isCheckedOut) {
            log.i { "Virtual sandbox result branch left unmoved: it is checked out in another worktree" }
        } else if (tip != snapshot) {
            git.call(current.source, "update-ref", "refs/heads/$branch", snapshot, tip.orEmpty())
        }
        log.i { "Virtual sandbox branch handed to user" }
        save(
            current.copy(state = current.state.copy(phase = VirtualSandboxPhase.HandedOff, resultCommit = snapshot)),
        ).state
    }

    /** A new run continues a handed-off sandbox; the next handoff moves the result branch to the new snapshot. */
    override suspend fun reopen(ref: WorkspaceRef): VirtualSandboxState = lock.withLock {
        val record = known(ref)
        if (record.state.phase != VirtualSandboxPhase.HandedOff) return@withLock record.state
        log.i { "Virtual sandbox reopened for a new run" }
        save(
            record.copy(
                state = record.state.copy(phase = VirtualSandboxPhase.Ready, resultCommit = null),
                handedOff = record.state.resultCommit,
            ),
        ).state
    }

    /** The push runs outside the profile lock; the commit is already saved (and pinned) when it starts. */
    override suspend fun commitToBranch(
        ref: WorkspaceRef,
        branch: String,
        message: String,
        isPush: Boolean,
        isForce: Boolean,
        expectedRevision: Long?,
    ): String {
        val committed = lock.withLock {
            val committed = branches.commit(branchReady(ref, expectedRevision), branch, message, isPush, isForce)
            save(committed.record)
            log.i { "Virtual sandbox snapshot committed to a branch force=$isForce" }
            committed
        }
        if (isPush) {
            // A failed push propagates; the caller (the sandbox tool refusal) logs it once.
            branches.push(committed, branch)
            log.i { "Virtual sandbox branch pushed to origin force=$isForce" }
            lock.withLock {
                // Origin now holds the pushed commit: it is the lease of the next forced push.
                read()[ref.value]?.let { save(it.copy(origins = it.origins + (branch to committed.commit))) }
            }
        }
        return committed.commit
    }

    override suspend fun checkoutRef(
        ref: WorkspaceRef,
        branch: String,
        isFetch: Boolean,
        at: String?,
        expectedRevision: Long?,
    ): VirtualSandboxState {
        val revision = fetched(ref, branch, isFetch, expectedRevision)
        return lock.withLock {
            val checkedOut = branches.checkout(branchReady(ref, revision), branch, isFetch, at)
            log.i { "Virtual sandbox files replaced by a branch" }
            save(checkedOut).state
        }
    }

    override suspend fun restorePaths(
        ref: WorkspaceRef,
        paths: List<String>,
        from: String,
        expectedRevision: Long?,
    ): VirtualSandboxState = lock.withLock {
        val restored = branches.restore(branchReady(ref, expectedRevision), paths, from)
        log.i { "Virtual sandbox paths restored from another commit count=${paths.size}" }
        save(restored).state
    }

    override suspend fun mergeRef(
        ref: WorkspaceRef,
        branch: String,
        isFetch: Boolean,
        expectedRevision: Long?,
    ): VirtualSandboxState {
        val revision = fetched(ref, branch, isFetch, expectedRevision)
        return lock.withLock {
            val current = branchReady(ref, revision)
            val merged = branches.merge(current, branch, isFetch) ?: return@withLock current.state
            log.i { "Virtual sandbox merged a branch (conflicts=${merged.state.conflicts.size})" }
            save(merged).state
        }
    }

    override suspend fun branchHistory(ref: WorkspaceRef, branch: String, base: String, isStat: Boolean): String =
        lock.withLock {
            branches.history(known(ref), branch, base, isStat).also {
                log.i { "Virtual sandbox branch history read stat=$isStat chars=${it.length}" }
            }
        }

    /**
     * Fetches [branch] outside the profile lock when [isFetch]; returns the revision the operation must still find
     * afterwards, so a sandbox that changed during the network call refuses instead of applying a stale approval.
     */
    private suspend fun fetched(ref: WorkspaceRef, branch: String, isFetch: Boolean, expectedRevision: Long?): Long {
        val record = lock.withLock { branchReady(ref, expectedRevision) }
        if (isFetch) branches.fetch(record, branch)
        return record.state.revision
    }

    /** Branch operations need a ready sandbox: a merge or rebase conflict must be resolved first. */
    private suspend fun branchReady(ref: WorkspaceRef, expectedRevision: Long?): VirtualRecord {
        val known = known(ref)
        check(known.state.phase != VirtualSandboxPhase.Conflicted && !known.isResolving) { "SandboxConflicted" }
        val record = accessible(ref)
        check(expectedRevision == null || record.state.revision == expectedRevision) { "SandboxRevisionChanged" }
        return record
    }

    /** A child's immutable result merges relative to the exact parent snapshot it inherited. */
    override suspend fun integrate(
        parent: WorkspaceRef,
        child: WorkspaceRef,
        expectedRevision: Long?,
        expectedSnapshot: String?,
    ): VirtualSandboxState = lock.withLock {
        val target = accessible(parent)
        // A pending conflict (being resolved) owns the pending view; an integration on top would orphan it.
        check(target.state.isReady && target.pendingSnapshot == null) { "SandboxConflicted" }
        val result = known(child)
        check(expectedRevision == null || target.state.revision == expectedRevision) { "SandboxRevisionChanged" }
        val childSnapshot = expectedSnapshot ?: result.state.snapshot
        val fork = result.snapshotForks[childSnapshot] ?: result.fork.takeIf { childSnapshot == result.state.snapshot }
        checkNotNull(fork) { "SandboxResultChanged" }
        check(result.parent == parent.value && result.common == target.common) { "SandboxResultNotOwned" }
        val merged = git.merge(target.source, fork, target.state.snapshot, childSnapshot)
        val snapshot = git.commit(target.source, merged.tree, target.state.baseCommit)
        git.pin(target.source, target.id, snapshot)
        save(
            target.copy(
                state = target.state.copy(
                    phase = if (merged.conflicts.isEmpty()) {
                        VirtualSandboxPhase.Ready
                    } else {
                        VirtualSandboxPhase.Conflicted
                    },
                    snapshot = if (merged.conflicts.isEmpty()) snapshot else target.state.snapshot,
                    revision = target.state.revision + 1,
                    conflicts = merged.conflicts,
                ),
                pendingBase = target.state.baseCommit.takeIf { merged.conflicts.isNotEmpty() },
                pendingSnapshot = snapshot.takeIf { merged.conflicts.isNotEmpty() },
                pending = VirtualPending.Integrate.takeIf { merged.conflicts.isNotEmpty() },
                pendingMerge = null,
            ),
        ).state
    }

    override suspend fun resolve(
        ref: WorkspaceRef,
        path: String,
        content: String?,
        expectedRevision: Long?,
    ): VirtualSandboxState = lock.withLock {
        val record = accessible(ref)
        check(expectedRevision == null || record.state.revision == expectedRevision) { "SandboxRevisionChanged" }
        check(record.isResolving && path in record.state.conflicts) { "SandboxConflictResolutionNotAuthorized" }
        requireClean(record.source)
        check(git.text(record.source, "rev-parse", "HEAD") == record.pendingBase) { "SandboxSourceChanged" }
        val snapshot = git.edit(record.source, checkNotNull(record.pendingSnapshot), mapOf(safePath(path) to content))
        val remaining = record.state.conflicts - path
        git.pin(record.source, record.id, snapshot)
        // A resolved branch merge becomes the next commit's second parent (or the caught-up tip) only now.
        val merged = record.pendingMerge?.takeIf { remaining.isEmpty() }?.let(record::withMerge) ?: record
        save(
            merged.copy(
                state = record.state.copy(
                    phase = if (remaining.isEmpty()) VirtualSandboxPhase.Ready else VirtualSandboxPhase.Conflicted,
                    conflicts = remaining,
                    revision = record.state.revision + 1,
                    baseCommit = if (remaining.isEmpty()) record.pendingBase else record.state.baseCommit,
                    snapshot = if (remaining.isEmpty()) snapshot else record.state.snapshot,
                    deadlineMillis = if (remaining.isEmpty()) null else record.state.deadlineMillis,
                    isResolving = remaining.isNotEmpty(),
                ),
                pendingSnapshot = snapshot.takeIf { remaining.isNotEmpty() },
                pendingBase = record.pendingBase.takeIf { remaining.isNotEmpty() },
                pending = record.pending.takeIf { remaining.isNotEmpty() },
                pendingMerge = record.pendingMerge.takeIf { remaining.isNotEmpty() },
                isResolving = remaining.isNotEmpty(),
            ),
        ).state
    }

    private inner class Handle(override val ref: WorkspaceRef, override val processDirectory: String) :
        VirtualWorkspace {
        override suspend fun revision(): Long = lock.withLock { accessible(ref).state.revision }

        override suspend fun sourceProject(): WorkspaceRef? = tasks?.value?.find(ref)?.project

        override suspend fun paths(): List<String> = lock.withLock {
            val record = accessible(ref)
            val view = if (record.isResolving) checkNotNull(record.pendingSnapshot) else record.state.snapshot
            git.entries(record.source, view).filterValues { it.type == "blob" }.keys.sorted()
        }

        override suspend fun read(path: String): String? = lock.withLock {
            readText(ref, path, forSearch = false)
        }

        override suspend fun readForSearch(path: String): String? = lock.withLock {
            readText(ref, path, forSearch = true)
        }

        override suspend fun scanTextFiles(paths: List<String>, visit: (String, String) -> Boolean) = lock.withLock {
            val record = accessible(ref)
            val view = if (record.isResolving) checkNotNull(record.pendingSnapshot) else record.state.snapshot
            val entries = git.entries(record.source, view)
            val selected = paths.mapNotNull { path -> entries[safePath(path)]?.let { path to it } }.toMap()
            git.scanTexts(record.source, selected, visit)
            accessible(ref)
            Unit
        }

        private suspend fun readText(ref: WorkspaceRef, path: String, forSearch: Boolean): String? {
            val record = accessible(ref)
            val view = if (record.isResolving) checkNotNull(record.pendingSnapshot) else record.state.snapshot
            val entry = git.entries(record.source, view)[safePath(path)] ?: return null
            return if (forSearch && entry.mode !in setOf("100644", "100755")) {
                null
            } else {
                check(entry.type == "blob" && entry.mode in setOf("100644", "100755")) { "SandboxLinkUnsupported" }
                textCache[entry.objectId] ?: loadText(record.source, entry.objectId, forSearch)
            }
        }

        private suspend fun loadText(source: String, objectId: String, forSearch: Boolean): String? {
            val bytes = git.call(source, "cat-file", "blob", objectId).bytes
            val text = bytes.decodeToString()
            val isText = 0.toByte() !in bytes && text.encodeToByteArray().contentEquals(bytes)
            if (forSearch && !isText) return null
            check(isText) { "SandboxBinaryFile" }
            while (textCache.isNotEmpty() && cachedChars + text.length > MAX_CACHE_CHARS) {
                cachedChars -= textCache.remove(textCache.keys.first())?.length ?: 0
            }
            if (text.length <= MAX_CACHE_CHARS) {
                textCache[objectId] = text
                cachedChars += text.length
            }
            return text
        }

        override suspend fun change(expectedRevision: Long, changes: List<VirtualFileChange>): Long = lock.withLock {
            val record = accessible(ref)
            check(record.state.isReady && record.state.revision == expectedRevision) { "SandboxRevisionChanged" }
            require(changes.isNotEmpty() && changes.map { it.path }.distinct().size == changes.size)
            val edits = changes.associate { safePath(it.path) to it.content }
            val view = git.edit(record.source, record.state.snapshot, edits)
            git.pin(record.source, record.id, view)
            save(
                record.copy(state = record.state.copy(snapshot = view, revision = expectedRevision + 1)),
            ).state.revision
        }

        override suspend fun move(expectedRevision: Long, path: String, destination: String): Long = lock.withLock {
            val record = accessible(ref)
            check(record.state.isReady && record.state.revision == expectedRevision) { "SandboxRevisionChanged" }
            val view = git.move(record.source, record.state.snapshot, safePath(path), safePath(destination))
            git.pin(record.source, record.id, view)
            save(
                record.copy(state = record.state.copy(snapshot = view, revision = expectedRevision + 1)),
            ).state.revision
        }
    }

    private suspend fun accessible(ref: WorkspaceRef): VirtualRecord {
        val record = known(ref)
        check(record.state.isReady || record.isResolving) { "SandboxPaused" }
        requireClean(record.source)
        check(
            record.reflogPath == null || reflogStamp(record.reflogPath) == record.reflogStamp,
        ) { "SandboxSourceChanged" }
        val expected = if (record.isResolving) record.pendingBase else record.state.baseCommit
        check(git.text(record.source, "rev-parse", "HEAD") == expected) { "SandboxSourceChanged" }
        return record
    }

    private suspend fun history(record: VirtualRecord): VirtualRecord {
        val path = record.reflogPath ?: return record
        val stamp = reflogStamp(path)
        if (stamp == record.reflogStamp) return record
        if (!withContext(dispatchers.io) { Files.isDirectory(Path.of(record.source)) }) return record
        val delta = withContext(dispatchers.io) { advanceReflog(path, record.reflogCursor) }
        return record.copy(
            reflogStamp = stamp,
            reflogCursor = delta.cursor,
            isHistoryChanged = record.isHistoryChanged || delta.isManualRequired || record.reflogCursor == null,
        )
    }

    private suspend fun reflogStamp(path: String): String? = withContext(dispatchers.io) {
        val file = Path.of(path)
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            null
        } else {
            val attributes = Files.readAttributes(
                file,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            )
            "${attributes.size()}:${attributes.lastModifiedTime()}"
        }
    }

    private suspend fun requireClean(source: String) {
        check(isClean(source)) { "SandboxSourceDirty" }
    }
    private suspend fun isClean(source: String): Boolean =
        git.text(source, "status", "--porcelain=v1", "--untracked-files=all").isEmpty()

    private suspend fun known(ref: WorkspaceRef): VirtualRecord = checkNotNull(
        read()[ref.value],
    ) { "SandboxUnavailable" }
    private suspend fun read(): Map<String, VirtualRecord> {
        cached?.let { return it }
        val raw = store.get(KEY)
        return (if (raw == null) emptyList() else Json.decodeFromString<List<VirtualRecord>>(raw))
            .associateBy { it.ref }.also { cached = it }
    }

    private suspend fun save(record: VirtualRecord): VirtualRecord {
        val saved = if (record.state.snapshot in record.snapshotForks) {
            record
        } else {
            record.copy(
                snapshotForks = record.snapshotForks + (record.state.snapshot to record.fork),
            )
        }
        if (read()[record.ref] == saved) return saved
        val next = read() + (record.ref to saved)
        store.set(KEY, Json.encodeToString(next.values.toList()))
        cached = next
        return saved
    }

    companion object {
        private val KEY = stringKey("records")
        private const val MAX_CACHE_CHARS = 32 * 1024 * 1024
        fun workspace(record: WorktreeRecord): WorkspaceRef = WorkspaceRef("sandbox-${record.provisioning}")
        fun safePath(value: String): String {
            require(
                value.isNotBlank() && '\u0000' !in value && '\\' !in value && ':' !in value,
            ) { "InvalidSandboxPath" }
            val path = Path.of(value)
            require(
                !path.isAbsolute && path.normalize().toString().replace('\\', '/') == value &&
                    path.none {
                        it.toString() == ".." || it.toString().trimEnd(
                            '.',
                            ' ',
                        ).equals(".git", ignoreCase = true)
                    },
            ) { "InvalidSandboxPath" }
            return value
        }
    }
}

@Serializable
internal data class VirtualRecord(
    val ref: String,
    val id: String,
    val source: String,
    val common: String,
    val state: VirtualSandboxState,
    val parent: String? = null,
    val fork: String,
    val forkBase: String? = null,
    val snapshotForks: Map<String, String> = emptyMap(),
    val reflogPath: String? = null,
    val reflogStamp: String? = null,
    val reflogCursor: ReflogCursor? = null,
    val isHistoryChanged: Boolean = false,
    val pendingBase: String? = null,
    val pendingSnapshot: String? = null,
    val isResolving: Boolean = false,
    val branchCommits: Map<String, String> = emptyMap(),
    /** Branch the sandbox continues after [DesktopVirtualWorkspaces.checkoutRef] or a branch commit. */
    val branch: String? = null,
    /** Result branch tip of a handoff before [DesktopVirtualWorkspaces.reopen]; the next handoff may move it. */
    val handedOff: String? = null,
    /** Merged tip that the next branch commit records as a second parent; a later merge supersedes it. */
    val mergeParent: String? = null,
    /** What produced [pendingSnapshot]; null in records from before it was recorded (see [keepsConflict]). */
    val pending: VirtualPending? = null,
    /** A conflicted branch merge, applied by [withMerge] once its conflicts are resolved and dropped with them. */
    val pendingMerge: PendingMerge? = null,
    /** Last commit this sandbox itself wrote per branch; a retry with the same files reuses it. */
    val ownCommits: Map<String, String> = emptyMap(),
    /**
     * Origin state per branch the sandbox has seen (fetched or contained in what it loaded or merged, or pushed):
     * the forced push lease.
     */
    val origins: Map<String, String> = emptyMap(),
    /** Local branch tip per branch observed by a checkout or written by a commit: a forced commit moves only it. */
    val localTips: Map<String, String> = emptyMap(),
) {
    /**
     * A merge or child integration conflict, which a loaded branch keeps when it re-anchors on the source. Records
     * from before [pending] was recorded hold a merge or integration conflict exactly when it is pending on the
     * sandbox base itself (a rebase conflict is pending on the new source HEAD); both are kept the same way.
     */
    val keepsConflict: Boolean
        get() = pendingSnapshot != null && when (pending) {
            VirtualPending.Merge, VirtualPending.Integrate -> true
            VirtualPending.Rebase -> false
            null -> pendingBase == state.baseCommit
        }

    override fun toString(): String = "VirtualRecord(id=$id, revision=${state.revision}, phase=${state.phase})"
}

/** Origin of a pending conflicted view. */
@Serializable
internal enum class VirtualPending { Rebase, Merge, Integrate }

/**
 * A branch merge waiting for conflict resolution: [theirs] becomes the caught-up tip of [branch] ([isCatchUp]) or
 * the second parent of the next commit; [origin] is the origin state of the sandbox's own branch seen by the merge.
 */
@Serializable
internal data class PendingMerge(
    val branch: String,
    val theirs: String,
    val isCatchUp: Boolean,
    val origin: String? = null,
)

/** Shared backend contract keeps both DI bindings on the same profile-owned instance. */
internal interface VirtualSandboxStore :
    VirtualWorkspaceProvider,
    VirtualSandboxBranches {
    suspend fun create(record: WorktreeRecord): WorkspaceRef
    suspend fun state(ref: WorkspaceRef): VirtualSandboxState
    suspend fun inspect(ref: WorkspaceRef): VirtualSandboxState
    suspend fun pause(ref: WorkspaceRef): VirtualSandboxState
    suspend fun sourceClean(ref: WorkspaceRef): Boolean
    suspend fun resume(ref: WorkspaceRef, explicit: Boolean, resolveConflicts: Boolean = false): VirtualSandboxState
    suspend fun snapshot(ref: WorkspaceRef): String
    suspend fun handoff(record: WorktreeRecord): VirtualSandboxState
    suspend fun reopen(ref: WorkspaceRef): VirtualSandboxState
    suspend fun integrate(
        parent: WorkspaceRef,
        child: WorkspaceRef,
        expectedRevision: Long? = null,
        expectedSnapshot: String? = null,
    ): VirtualSandboxState
    suspend fun resolve(
        ref: WorkspaceRef,
        path: String,
        content: String?,
        expectedRevision: Long? = null,
    ): VirtualSandboxState
}

/** Operations between a sandbox and branches of the real repository; no checkout or working file is touched. */
internal interface VirtualSandboxBranches {
    /** [expectedRevision] is the approved revision; a changed sandbox refuses with `SandboxRevisionChanged`. */
    suspend fun commitToBranch(
        ref: WorkspaceRef,
        branch: String,
        message: String,
        isPush: Boolean = false,
        isForce: Boolean = false,
        expectedRevision: Long? = null,
    ): String
    suspend fun checkoutRef(
        ref: WorkspaceRef,
        branch: String,
        isFetch: Boolean,
        at: String? = null,
        expectedRevision: Long? = null,
    ): VirtualSandboxState
    suspend fun restorePaths(
        ref: WorkspaceRef,
        paths: List<String>,
        from: String,
        expectedRevision: Long? = null,
    ): VirtualSandboxState
    suspend fun mergeRef(
        ref: WorkspaceRef,
        branch: String,
        isFetch: Boolean,
        expectedRevision: Long? = null,
    ): VirtualSandboxState
    suspend fun branchHistory(ref: WorkspaceRef, branch: String, base: String, isStat: Boolean): String
}
