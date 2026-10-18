package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.removeSlot
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.tryLockResource
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.validateSlotOwnership
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Git argv calls and canonical path validation; native stderr is never allowed into logs or exceptions. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopWorktreeGit(
    private val dispatchers: DispatcherProvider,
    private val virtual: Lazy<VirtualSandboxStore> = lazy { error("VirtualWorkspaceBackendUnavailable") },
    private val builds: Lazy<WorktreeBuildCoordinator>? = null,
) : WorktreeGit {
    private val log = Log.tag("WorktreeGit")
    override val isAvailable: Boolean = true

    override suspend fun plan(source: String, identity: String): WorktreeProvision = withContext(dispatchers.io) {
        require(identity.matches(Regex("[a-zA-Z0-9-]+"))) { "InvalidProvisionIdentity" }
        val root = Path.of(
            gitOptional(source, "rev-parse", "--show-toplevel") ?: error("GitRepositoryRequired"),
        ).toRealPath()
        val common = Path.of(
            git(root.toString(), "rev-parse", "--path-format=absolute", "--git-common-dir"),
        ).toRealPath()
        val base = gitOptional(root.toString(), "rev-parse", "--verify", "HEAD^{commit}") ?: error("GitHeadRequired")
        val sourceBranch = gitOptional(root.toString(), "symbolic-ref", "--quiet", "--short", "HEAD")
        val parent = common.resolve("heartbeat-worktree-data").resolve("checkouts")
        val directory = parent.resolve(identity).normalize()
        check(directory.startsWith(common) && directory != root) { "InvalidCheckoutDirectory" }
        WorktreeProvision(
            root.toString(),
            directory.toString(),
            common.toString(),
            sourceBranch,
            "$BRANCH_PREFIX$identity",
            base,
            pullRequestBase = sourceBranch ?: remoteDefaultBranch(root.toString()),
        )
    }

    override suspend fun materialize(record: WorktreeRecord) = withContext(dispatchers.io) {
        validateRecord(record)
        if (record.task.virtualSandbox != null) {
            virtual.value.create(record)
            return@withContext
        }
        if (exists(record)) return@withContext
        val directory = Path.of(record.directory)
        check(!Files.exists(directory)) { "CheckoutDirectoryOccupied" }
        Files.createDirectories(directory.parent)
        git(
            record.sourceDirectory,
            "worktree",
            "add",
            "-b",
            checkNotNull(record.task.branch),
            record.directory,
            checkNotNull(record.task.baseCommit),
        )
        check(exists(record)) { "CheckoutCreationUnconfirmed" }
    }

    override suspend fun trackMain(source: String): WorktreeProvision = withContext(dispatchers.io) {
        val root = Path.of(git(source, "rev-parse", "--show-toplevel")).toRealPath()
        val common = Path.of(
            git(root.toString(), "rev-parse", "--path-format=absolute", "--git-common-dir"),
        ).toRealPath()
        val base = git(root.toString(), "rev-parse", "--verify", "HEAD^{commit}")
        val branch = gitOptional(root.toString(), "symbolic-ref", "--quiet", "--short", "HEAD")
        WorktreeProvision(root.toString(), root.toString(), common.toString(), branch, branch ?: "detached", base)
    }

    override suspend fun exists(record: WorktreeRecord): Boolean = withContext(dispatchers.io) {
        validateRecord(record)
        if (record.task.virtualSandbox != null) {
            return@withContext virtual.value.find(DesktopVirtualWorkspaces.workspace(record)) != null
        }
        val directory = Path.of(record.directory)
        if (!Files.isDirectory(directory)) return@withContext false
        if (directory.toRealPath() != directory.toAbsolutePath().normalize()) return@withContext false
        val common = gitOptional(
            record.directory,
            "rev-parse",
            "--path-format=absolute",
            "--git-common-dir",
        ) ?: return@withContext false
        val branch = gitOptional(record.directory, "symbolic-ref", "--quiet", "--short", "HEAD")
        Path.of(
            common,
        ).toRealPath() == Path.of(
            record.commonDirectory,
        ).toRealPath() && (!record.task.isIsolated || branch == record.task.branch)
    }

    override suspend fun validatePlan(record: WorktreeRecord, plan: WorktreeBuildPlan) = withContext(dispatchers.io) {
        check(exists(record)) { "WorktreeUnavailable" }
        require(plan.system.isNotBlank() && plan.system.length <= MAX_NAME) { "InvalidBuildSystem" }
        require(plan.commands.isNotEmpty() && plan.commands.size <= MAX_COMMANDS) { "InvalidBuildCommands" }
        require(plan.commands.map { it.id }.distinct().size == plan.commands.size) { "DuplicateBuildCommands" }
        require(plan.caches.map { it.id }.distinct().size == plan.caches.size) { "DuplicateBuildCaches" }
        plan.caches.forEach { cache ->
            require(cache.id.matches(IDENTIFIER)) { "InvalidCacheIdentity" }
            cacheDirectory(record, cache.directory)
            require(
                plan.commands.any { command ->
                    command.arguments.any { it.contains("{cache:${cache.id}}") } ||
                        command.environment.values.any { it.contains("{cache:${cache.id}}") }
                },
            ) { "UnusedBuildCache" }
        }
        plan.exclusiveResources.forEach { canonicalResource(record, it) }
        plan.commands.forEach { command ->
            require(command.id.matches(IDENTIFIER) && command.executable.isNotBlank()) { "InvalidBuildCommand" }
            require(
                command.arguments.size <= MAX_ARGUMENTS && command.environment.size <= MAX_ENVIRONMENT,
            ) { "BuildCommandTooLarge" }
            require(command.timeoutMillis in MIN_BUILD_TIMEOUT..MAX_BUILD_TIMEOUT) { "InvalidBuildTimeout" }
            require(Json.encodeToString(command).length <= MAX_BUILD_PRESENTATION) { "BuildCommandTooLarge" }
            require(command.environment.keys.all { ENVIRONMENT.matches(it) }) { "InvalidBuildEnvironment" }
            require(command.environment.keys.none(::isSecretEnvironmentName)) { "SecretBuildEnvironment" }
            require(command.environment.keys.none { it in FORBIDDEN_ENVIRONMENT }) { "ProtectedBuildEnvironment" }
            checkoutDirectory(record, command.directory)
            // Shared resources can only be protected while the entire build remains in the worker's process tree.
            require(
                command.arguments.none { it == "--daemon" || it == "--background" || it == "--detach" },
            ) {
                "DetachedBuildUnsupported"
            }
        }
    }

    override suspend fun build(record: WorktreeRecord, id: String, command: String): BuildExecution = withContext(
        dispatchers.io,
    ) {
        require(id.matches(IDENTIFIER)) { "InvalidBuildIdentity" }
        val plan = checkNotNull(record.task.buildPlan) { "BuildConfigurationRequired" }
        validatePlan(record, plan)
        val selected = plan.commands.singleOrNull { it.id == command } ?: error("UnknownBuildCommand")
        val caches = plan.caches.associate { it.id to cacheDirectory(record, it.directory).toString() }
        fun substitute(value: String): String {
            var expanded = value
            caches.forEach { (key, directory) -> expanded = expanded.replace("{cache:$key}", directory) }
            require(!expanded.contains("{cache:")) { "UnknownCacheReference" }
            return expanded
        }
        val resolved = selected.copy(
            executable = substitute(selected.executable),
            arguments = foregroundArguments(selected.executable, selected.arguments.map(::substitute)),
            environment = selected.environment.mapValues { substitute(it.value) },
        )
        // Concurrent caches coordinate their own processes and must not serialize otherwise independent builds.
        val exclusiveCaches = plan.caches.filterNot { it.isConcurrent }.map { checkNotNull(caches[it.id]) }
        // A sandbox builds in its own slot, so only the original-checkout tasks serialize on the repository.
        val repository = if (record.task.isSandbox) emptyList() else listOf(record.commonDirectory)
        val resources = repository + exclusiveCaches + plan.exclusiveResources.map {
            canonicalResource(record, it).toString()
        }
        val data = Path.of(record.commonDirectory).resolve("heartbeat-worktree-data")
        val job = data.resolve("jobs").resolve(id)
        val directory = checkoutDirectory(record, selected.directory)
        val virtualState = if (record.task.virtualSandbox != null) {
            val ref = DesktopVirtualWorkspaces.workspace(record)
            virtual.value.snapshot(ref)
            virtual.value.state(ref)
        } else {
            null
        }
        val sandbox = if (record.task.isSandbox) {
            SandboxBuild(
                snapshot = virtualState?.snapshot.orEmpty(),
                pool = data.resolve(SLOT_POOL).toString(),
                // The original checkout outlives the task, so slots stay creatable after the sandbox is removed.
                repository = record.sourceDirectory,
                relativeDirectory = Path.of(buildRoot(record)).toRealPath().relativize(directory).toString()
                    .ifEmpty { "." },
                affinity = record.provisioning,
                baseCommit = virtualState?.baseCommit,
            )
        } else {
            null
        }
        BuildExecution(
            id,
            resolved,
            directory.toString(),
            resources.distinct().sorted(),
            job.toString(),
            parentProcess = ProcessHandle.current().pid(),
            parentStartedAt = ProcessHandle.current().info().startInstant().orElse(null)?.toString(),
            configurationRevision = record.task.buildConfigurationRevision,
            project = projectKey(record),
            sandbox = sandbox,
        )
    }

    override suspend fun reclaim(record: WorktreeRecord) = withContext(dispatchers.io) {
        if (record.task.virtualSandbox != null) return@withContext
        check(record.task.isSandbox && exists(record)) { "SandboxUnavailable" }
        git(record.directory, "clean", "-d", "-X", "--force", "--quiet")
        Unit
    }

    override suspend fun discardSnapshot(record: WorktreeRecord, id: String) = withContext(dispatchers.io) {
        require(id.matches(IDENTIFIER)) { "InvalidBuildIdentity" }
        gitOptional(record.sourceDirectory, "update-ref", "-d", "$SNAPSHOT_REFS$id")
        Unit
    }

    override suspend fun pruneSnapshots(record: WorktreeRecord, active: Set<String>) = withContext(dispatchers.io) {
        val listing = gitOptional(
            record.sourceDirectory,
            "for-each-ref",
            "--format=%(refname) %(committerdate:unix)",
            SNAPSHOT_REFS,
        ) ?: return@withContext
        val cutoff = System.currentTimeMillis() / MILLIS_PER_SECOND - SNAPSHOT_GRACE_SECONDS
        val stale = listing.lineSequence().mapNotNull { line ->
            val (ref, time) = line.split(' ').takeIf { it.size == 2 } ?: return@mapNotNull null
            val id = ref.removePrefix(SNAPSHOT_REFS)
            ref.takeIf { id.matches(IDENTIFIER) && id !in active && (time.toLongOrNull() ?: 0) < cutoff }
        }.toList()
        stale.forEach { gitOptional(record.sourceDirectory, "update-ref", "-d", it) }
        if (stale.isNotEmpty()) log.i { "Pruned stale sandbox snapshots count=${stale.size}" }
    }

    override suspend fun sweepSlots(record: WorktreeRecord, keep: Int) = withContext(dispatchers.io) {
        require(keep >= 0) { "InvalidSlotCount" }
        val data = Path.of(record.commonDirectory).toRealPath().resolve("heartbeat-worktree-data")
        val pool = data.resolve(SLOT_POOL)
        if (Files.isSymbolicLink(data) || Files.isSymbolicLink(pool)) return@withContext false
        val registered = gitOptional(record.sourceDirectory, "worktree", "list", "--porcelain").orEmpty()
            .lineSequence().filter { it.startsWith("worktree ") }
            .map { Path.of(it.removePrefix("worktree ")).normalize() }
        val present = if (Files.isDirectory(pool)) {
            Files.list(pool).use { files ->
                files.map { file ->
                    val name = file.fileName.toString().removeSuffix(
                        ".unavailable",
                    ).removeSuffix(".transaction").removeSuffix(".affinity")
                    pool.resolve(name)
                }.toList()
            }
        } else {
            emptyList()
        }
        val surplus = (registered + present).filter { it.parent == pool }.distinct().filter { slot ->
            SLOT_NAME.matchEntire(
                slot.fileName.toString(),
            )?.groupValues?.get(1)?.toIntOrNull()?.let { it >= keep } == true
        }.toList()
        val occupied = builds?.value?.occupiedSlots(record.commonDirectory).orEmpty()
        val removed = surplus.count { slot ->
            val index = SLOT_NAME.matchEntire(slot.fileName.toString())?.groupValues?.get(1)?.toIntOrNull()
            index !in occupied && removeFreeSlot(record, slot)
        }
        if (removed > 0) log.i { "Removed surplus build slots count=$removed keep=$keep" }
        removed < surplus.size
    }

    /** The slot's worker lock is held for the whole removal, so no build can start in it meanwhile. */
    private suspend fun removeFreeSlot(record: WorktreeRecord, slot: Path): Boolean {
        val held = tryLockResource(slot.toString()) ?: return false
        return try {
            validateSlotOwnership(slot, Path.of(record.commonDirectory).toRealPath())
            gitOptional(record.sourceDirectory, "worktree", "remove", "--force", slot.toString())
            if (Files.exists(slot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) removeSlot(slot)
            Files.deleteIfExists(slot.resolveSibling("${slot.fileName}.transaction"))
            Files.deleteIfExists(slot.resolveSibling("${slot.fileName}.unavailable"))
            Files.deleteIfExists(slot.resolveSibling("${slot.fileName}.affinity"))
            true
        } finally {
            held.close()
        }
    }

    override suspend fun snapshot(record: WorktreeRecord, id: String): String = withContext(dispatchers.io) {
        require(id.matches(IDENTIFIER)) { "InvalidBuildIdentity" }
        check(record.task.isSandbox && exists(record)) { "SandboxUnavailable" }
        val commit = if (record.task.virtualSandbox != null) {
            virtual.value.snapshot(DesktopVirtualWorkspaces.workspace(record))
        } else {
            commitFiles(record, "Heartbeat sandbox snapshot")
        }
        git(record.sourceDirectory, "update-ref", "$SNAPSHOT_REFS$id", commit)
        log.d { "Sandbox snapshot captured operation=$id" }
        commit
    }

    override suspend fun seal(record: WorktreeRecord, id: String): String = withContext(dispatchers.io) {
        require(id.matches(IDENTIFIER)) { "InvalidSealIdentity" }
        check(record.task.isIsolated && exists(record)) { "SealUnavailable" }
        val commit = if (record.task.virtualSandbox != null) {
            virtual.value.snapshot(DesktopVirtualWorkspaces.workspace(record))
        } else {
            commitFiles(record, "Heartbeat result")
        }
        git(record.sourceDirectory, "update-ref", "$RESULT_REFS$id", commit)
        log.d { "Checkout result sealed operation=$id" }
        commit
    }

    /** The files of the checkout as a commit over HEAD, built in a private index copy: nothing of the agent changes. */
    private suspend fun commitFiles(record: WorktreeRecord, message: String): String {
        val gitDirectory = Path.of(git(record.directory, "rev-parse", "--absolute-git-dir"))
        val index = Files.createTempFile("heartbeat-snapshot-", ".index")
        try {
            // A copy of the agent's index keeps stat data, so only changed files are hashed again.
            val current = gitDirectory.resolve("index")
            if (Files.isRegularFile(current)) {
                Files.copy(current, index, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.delete(index)
            }
            val environment = mapOf("GIT_INDEX_FILE" to index.toString()) + SNAPSHOT_IDENTITY
            git(record.directory, "add", "--all", environment = environment)
            val tree = git(record.directory, "write-tree", environment = environment)
            val head = checkNotNull(gitOptional(record.directory, "rev-parse", "--verify", "HEAD^{commit}")) {
                "SandboxBaseUnavailable"
            }
            return git(record.directory, "commit-tree", tree, "-p", head, "-m", message, environment = environment)
        } finally {
            Files.deleteIfExists(index)
        }
    }

    override suspend fun actionPrompt(record: WorktreeRecord, merge: Boolean): String {
        check(exists(record)) { "WorktreeUnavailable" }
        val source = record.task.sourceBranch
        if (merge) {
            checkNotNull(source) { "OriginalBranchUnavailable" }
            val target = mergePreflight(record)
            check(record.mergeTargetCommit == null || target == record.mergeTargetCommit) { "OriginalBranchMoved" }
            return "Merge task branch '${checkNotNull(record.task.branch)}' locally into branch '$source' " +
                "in the original checkout '${record.sourceDirectory}' whose captured HEAD is '$target'. " +
                "Verify this target " +
                "has not moved before merging. Do not push. Inspect dirty files and conflicts; " +
                "never overwrite other work. Keep this session bound to its worktree. " +
                "Worktree verification must already precede this merge. The repository build lease is held for " +
                "this entire turn: inspect, commit and merge with Git only; do not run builds during the merge. " +
                "After success call mark_task_complete " +
                "with a concise result. If blocked, explain the issue without claiming completion."
        }
        val target = record.pullRequestBase ?: source ?: error("PullRequestBaseUnavailable")
        return "Create a pull request for task branch '${checkNotNull(record.task.branch)}' against branch " +
            "'$target' using this same session and checkout. " +
            "Follow repository review and commit instructions; preserve existing work. " +
            "After creation call mark_task_complete with the verified " +
            "pullRequestUrl and a concise result. If blocked, explain the issue without claiming completion."
    }

    override suspend fun mergeLease(record: WorktreeRecord, id: String): BuildExecution = withContext(dispatchers.io) {
        require(id.matches(IDENTIFIER)) { "InvalidMergeIdentity" }
        val common = Path.of(record.commonDirectory).toRealPath()
        BuildExecution(
            id,
            io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand("merge-lease", "unused"),
            record.directory,
            listOf(common.toString()),
            common.resolve("heartbeat-worktree-data").resolve("jobs").resolve(id).toString(),
            isHold = true,
            parentProcess = ProcessHandle.current().pid(),
            parentStartedAt = ProcessHandle.current().info().startInstant().orElse(null)?.toString(),
            project = common.toString(),
        )
    }

    override suspend fun mergePreflight(record: WorktreeRecord): String {
        val source = record.task.sourceBranch ?: error("OriginalBranchUnavailable")
        check(
            gitOptional(record.sourceDirectory, "symbolic-ref", "--quiet", "--short", "HEAD") == source,
        ) { "OriginalBranchChanged" }
        check(git(record.sourceDirectory, "status", "--porcelain").isEmpty()) { "OriginalCheckoutDirty" }
        return git(record.sourceDirectory, "rev-parse", "--verify", "HEAD^{commit}")
    }

    override suspend fun verifyAction(record: WorktreeRecord, merge: Boolean, pullRequestUrl: String?): Boolean =
        if (merge) verifyMerge(record) else verifyPullRequest(record, pullRequestUrl)

    private suspend fun verifyMerge(record: WorktreeRecord): Boolean {
        val source = record.task.sourceBranch ?: return false
        val current = gitOptional(record.sourceDirectory, "symbolic-ref", "--quiet", "--short", "HEAD")
        val head = gitOptional(record.directory, "rev-parse", "--verify", "HEAD^{commit}")
        val isClean = git(record.directory, "status", "--porcelain").isEmpty() &&
            git(record.sourceDirectory, "status", "--porcelain").isEmpty()
        val target = "refs/heads/$source"
        val original = record.mergeTargetCommit
        val isOriginalPreserved = original == null || isAncestor(record, original, target)
        val isWorktreeMerged = head != null && isAncestor(record, head, target)
        val isRoute = current == source && isClean
        return isRoute && isOriginalPreserved && isWorktreeMerged
    }

    private suspend fun isAncestor(record: WorktreeRecord, commit: String, target: String): Boolean =
        gitOptional(record.sourceDirectory, "merge-base", "--is-ancestor", commit, target) != null

    private suspend fun verifyPullRequest(record: WorktreeRecord, url: String?): Boolean {
        if (url == null) return false
        val uri = parsePullRequestUrl(url) ?: return false
        val remote = gitOptional(record.directory, "remote", "get-url", "origin") ?: return false
        val repository = remoteRepository(remote) ?: return false
        if (!matchesRepository(uri, repository)) return false
        val result = process(
            record.directory,
            listOf("gh", "pr", "view", url, "--json", "url,headRefName,baseRefName,headRefOid"),
            optional = true,
        )
            ?: return false
        val data = Json.parseToJsonElement(result).jsonObject
        val head = gitOptional(record.directory, "rev-parse", "--verify", "HEAD^{commit}")
        val isHead = data["headRefName"]?.jsonPrimitive?.content == record.task.branch &&
            data["headRefOid"]?.jsonPrimitive?.content == head
        return data["url"]?.jsonPrimitive?.content == url && isHead &&
            matchesTargetBranch(record, data["baseRefName"]?.jsonPrimitive?.content)
    }

    private fun parsePullRequestUrl(url: String): java.net.URI? {
        val uri = try {
            java.net.URI(url)
        } catch (error: java.net.URISyntaxException) {
            log.w(IllegalArgumentException("InvalidPullRequestUrl (${error::class.simpleName.orEmpty()})")) {
                "Agent returned an invalid pull request URL"
            }
            return null
        }
        val isHttps = uri.scheme == "https" && uri.host != null
        val isPlain = uri.userInfo == null && uri.query == null && uri.fragment == null
        return uri.takeIf { isHttps && isPlain }
    }

    private fun matchesRepository(uri: java.net.URI, repository: Pair<String, String>): Boolean =
        uri.host.equals(repository.first, ignoreCase = true) && uri.path.startsWith("/${repository.second}/pull/")
    private fun matchesTargetBranch(record: WorktreeRecord, branch: String?): Boolean =
        branch != null && branch == (record.pullRequestBase ?: record.task.sourceBranch)

    private suspend fun remoteDefaultBranch(directory: String): String? = gitOptional(
        directory,
        "symbolic-ref",
        "--quiet",
        "--short",
        "refs/remotes/origin/HEAD",
    )?.removePrefix("origin/")

    private fun projectKey(record: WorktreeRecord): String = Path.of(record.commonDirectory).toRealPath().toString()

    private fun validateRecord(record: WorktreeRecord) {
        if (!record.task.isIsolated) {
            check(
                Path.of(
                    record.directory,
                ).toAbsolutePath().normalize() == Path.of(record.sourceDirectory).toAbsolutePath().normalize(),
            ) {
                "InvalidOriginalCheckoutIdentity"
            }
            return
        }
        val common = Path.of(record.commonDirectory).toAbsolutePath().normalize()
        val expected = common.resolve(
            "heartbeat-worktree-data",
        ).resolve("checkouts").resolve(record.provisioning).normalize()
        check(
            record.provisioning.matches(
                Regex("[a-zA-Z0-9-]+"),
            ) && Path.of(record.directory).toAbsolutePath().normalize() == expected,
        ) {
            "InvalidCheckoutIdentity"
        }
    }

    private fun checkoutDirectory(record: WorktreeRecord, relative: String): Path {
        val input = Path.of(relative)
        require(!input.isAbsolute) { "BuildDirectoryMustBeRelative" }
        val root = Path.of(buildRoot(record)).toRealPath()
        val resolved = root.resolve(input).normalize()
        if (record.task.virtualSandbox != null) {
            require(
                resolved.startsWith(root) && input.none { it.toString() == ".git" },
            ) { "BuildDirectoryOutsideCheckout" }
            return resolved
        }
        require(resolved.startsWith(root) && Files.isDirectory(resolved) && resolved.toRealPath().startsWith(root)) {
            "BuildDirectoryOutsideCheckout"
        }
        return resolved.toRealPath()
    }

    private fun cacheDirectory(record: WorktreeRecord, location: String): Path {
        val result = canonicalResource(record, location)
        val source = Path.of(record.sourceDirectory).toRealPath()
        val checkout = Path.of(buildRoot(record)).toRealPath()
        val common = Path.of(record.commonDirectory).toRealPath()
        require(
            result != source && result != checkout && (
                !record.task.isIsolated || !result.startsWith(
                    checkout,
                )
            ) && !result.startsWith(common),
        ) {
            "InvalidSharedCacheDirectory"
        }
        require(
            if (record.task.virtualSandbox != null) {
                !result.startsWith(source)
            } else {
                !result.startsWith(source.resolve("build")) && !result.startsWith(source.resolve(".gradle"))
            },
        ) {
            "ProjectOutputsCannotBeSharedCaches"
        }
        return result
    }

    private fun canonicalResource(record: WorktreeRecord, location: String): Path {
        require(location.isNotBlank()) { "InvalidBuildResource" }
        val input = Path.of(location)
        val resolved = (
            if (input.isAbsolute) {
                input
            } else {
                Path.of(
                    record.sourceDirectory,
                ).resolve(input)
            }
        ).toAbsolutePath().normalize()
        var ancestor = resolved
        while (!Files.exists(ancestor)) ancestor = checkNotNull(ancestor.parent) { "InvalidBuildResource" }
        return ancestor.toRealPath().resolve(ancestor.relativize(resolved)).normalize()
    }

    private fun buildRoot(record: WorktreeRecord): String =
        if (record.task.virtualSandbox != null) record.sourceDirectory else record.directory

    override val isVirtualSupported: Boolean get() = true

    override suspend fun pauseVirtual(record: WorktreeRecord) =
        virtual.value.pause(DesktopVirtualWorkspaces.workspace(record))

    override suspend fun virtualSourceClean(record: WorktreeRecord): Boolean =
        virtual.value.sourceClean(DesktopVirtualWorkspaces.workspace(record))

    override suspend fun virtualWorkspace(record: WorktreeRecord) =
        if (record.task.virtualSandbox != null) DesktopVirtualWorkspaces.workspace(record) else null

    override suspend fun inspectVirtual(record: WorktreeRecord) = if (record.task.virtualSandbox != null) {
        virtual.value.inspect(
            DesktopVirtualWorkspaces.workspace(record),
        )
    } else {
        null
    }

    override suspend fun resumeVirtual(record: WorktreeRecord, explicit: Boolean, resolveConflicts: Boolean) =
        if (record.task.virtualSandbox != null) {
            virtual.value.resume(DesktopVirtualWorkspaces.workspace(record), explicit, resolveConflicts)
        } else {
            null
        }

    override suspend fun handoffVirtual(record: WorktreeRecord) =
        if (record.task.virtualSandbox != null) virtual.value.handoff(record) else null

    private suspend fun git(
        directory: String,
        vararg arguments: String,
        environment: Map<String, String> = emptyMap(),
    ): String = checkNotNull(
        process(directory, listOf("git", "--no-optional-locks") + arguments, optional = false, environment),
    )

    private suspend fun gitOptional(directory: String, vararg arguments: String): String? =
        process(directory, listOf("git", "--no-optional-locks") + arguments, optional = true)

    private suspend fun process(
        directory: String,
        arguments: List<String>,
        optional: Boolean,
        environment: Map<String, String> = emptyMap(),
    ): String? = withContext(dispatchers.io) {
        val output = Files.createTempFile("heartbeat-git-", ".out")
        val hooks = Files.createTempDirectory("heartbeat-hooks-")
        var child: Process? = null
        try {
            val command = if (arguments.first() == "git") {
                arguments.take(2) + listOf(
                    "-c",
                    "core.fsmonitor=false",
                    "-c",
                    "core.hooksPath=$hooks",
                    "-c",
                    "maintenance.auto=false",
                    "-c",
                    "gc.auto=0",
                ) + arguments.drop(2)
            } else {
                arguments
            }
            child = ProcessBuilder(command).directory(Path.of(directory).toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).apply {
                    environment().keys.filter { it.startsWith("GIT_") }.forEach { environment().remove(it) }
                    environment().putAll(environment)
                    environment()["GIT_NO_REPLACE_OBJECTS"] = "1"
                    environment()["GIT_NO_LAZY_FETCH"] = "1"
                }.start()
            awaitChild(child)
            readGitOutput(child, output, optional)
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            log.w(
                IllegalStateException("GitProcessTimedOut (${error::class.simpleName.orEmpty()})"),
            ) { "Git helper timed out" }
            error("GitProcessTimedOut")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(
                IllegalStateException("GitProcessFailed (${error::class.simpleName.orEmpty()})"),
            ) { "Git helper failed" }
            if (optional) null else error("GitProcessUnavailable")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                stopNative(child)
                cleanupGitTemporary(output)
                cleanupGitTemporary(hooks)
            }
        }
    }

    private suspend fun awaitChild(child: Process) = withTimeout(PROCESS_TIMEOUT) {
        while (!child.waitFor(PROCESS_POLL, TimeUnit.MILLISECONDS)) {
            coroutineContext.ensureActive()
            delay(PROCESS_POLL)
        }
    }

    private fun readGitOutput(child: Process, output: Path, optional: Boolean): String? = if (child.exitValue() == 0) {
        Files.readString(output).trim()
    } else {
        check(optional) { "GitOperationFailed" }
        null
    }

    private fun stopNative(child: Process?) {
        if (child == null) return
        child.descendants().use { stream -> stream.filter { it.isAlive }.forEach { it.destroyForcibly() } }
        if (child.isAlive) child.destroyForcibly()
        child.waitFor(PROCESS_TIMEOUT, TimeUnit.MILLISECONDS)
    }

    private companion object {
        /** Task branches stay in the application namespace and never reuse an agent or user prefix. */
        const val BRANCH_PREFIX = "heartbeat/worktree-"
        const val SLOT_POOL = "slots"
        val SLOT_NAME = Regex("slot-([0-9]{1,3})")
        const val SNAPSHOT_REFS = "refs/heartbeat/snapshots/"

        /** Sealed results are kept for their integrators, so the snapshot pruner never scans this namespace. */
        const val RESULT_REFS = "refs/heartbeat/results/"

        /** Another profile may still queue a build of an older snapshot; a day covers any build timeout. */
        const val SNAPSHOT_GRACE_SECONDS = 86_400L
        const val MILLIS_PER_SECOND = 1_000L

        /** Snapshots are private build inputs and must not depend on a configured user identity. */
        val SNAPSHOT_IDENTITY = mapOf(
            "GIT_AUTHOR_NAME" to "Heartbeat",
            "GIT_AUTHOR_EMAIL" to "sandbox@heartbeat.invalid",
            "GIT_COMMITTER_NAME" to "Heartbeat",
            "GIT_COMMITTER_EMAIL" to "sandbox@heartbeat.invalid",
        )
        const val MAX_NAME = 256
        const val MAX_COMMANDS = 64
        const val MAX_ARGUMENTS = 256
        const val MAX_ENVIRONMENT = 64
        const val MAX_BUILD_PRESENTATION = 8_192
        const val PROCESS_TIMEOUT = 60_000L
        const val PROCESS_POLL = 50L
        const val MIN_BUILD_TIMEOUT = 1_000L
        const val MAX_BUILD_TIMEOUT = 86_400_000L
        val IDENTIFIER = Regex("[A-Za-z0-9_-]{1,128}")
        val ENVIRONMENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val FORBIDDEN_ENVIRONMENT = setOf(
            "LD_PRELOAD",
            "DYLD_INSERT_LIBRARIES",
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
        )
    }
}

/** Known daemon build systems are forced to keep their accepted work inside the lock-owning worker tree. */
internal fun foregroundArguments(executable: String, arguments: List<String>): List<String> {
    val name = Path.of(executable).fileName.toString().lowercase()
    return when {
        name.startsWith("gradle") -> arguments + listOf("--no-daemon", "--build-cache").filterNot { it in arguments }
        name.startsWith("dotnet") && "--disable-build-servers" !in arguments -> arguments + "--disable-build-servers"
        else -> arguments
    }
}

private fun remoteRepository(remote: String): Pair<String, String>? {
    val match = Regex(
        "(?:https://|ssh://git@|git@)([^/:]+)[:/]([^?#]+?)(?:\\.git)?$",
    ).matchEntire(remote) ?: return null
    return match.groupValues[1] to match.groupValues[2].removeSuffix(".git").trimEnd('/')
}
