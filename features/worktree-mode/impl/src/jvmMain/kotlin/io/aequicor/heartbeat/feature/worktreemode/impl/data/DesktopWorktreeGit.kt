package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
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
internal class DesktopWorktreeGit(private val dispatchers: DispatcherProvider) : WorktreeGit {
    private val log = Log.tag("WorktreeGit")
    override val isAvailable: Boolean = true

    override suspend fun plan(source: String, identity: String): WorktreeProvision = withContext(dispatchers.io) {
        require(identity.matches(Regex("[a-zA-Z0-9-]+"))) { "InvalidProvisionIdentity" }
        val root = Path.of(git(source, "rev-parse", "--show-toplevel")).toRealPath()
        val common = Path.of(
            git(root.toString(), "rev-parse", "--path-format=absolute", "--git-common-dir"),
        ).toRealPath()
        val base = git(root.toString(), "rev-parse", "--verify", "HEAD^{commit}")
        val sourceBranch = gitOptional(root.toString(), "symbolic-ref", "--quiet", "--short", "HEAD")
        val parent = common.resolve("heartbeat-worktree-data").resolve("checkouts")
        val directory = parent.resolve(identity).normalize()
        check(directory.startsWith(common) && directory != root) { "InvalidCheckoutDirectory" }
        WorktreeProvision(
            root.toString(),
            directory.toString(),
            common.toString(),
            sourceBranch,
            "codex/worktree-$identity",
            base,
            pullRequestBase = sourceBranch ?: remoteDefaultBranch(root.toString()),
        )
    }

    override suspend fun materialize(record: WorktreeRecord) = withContext(dispatchers.io) {
        validateRecord(record)
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
        require(plan.caches.isNotEmpty()) { "NeedsConfiguration" }
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
            if (Path.of(command.executable).fileName.toString().lowercase().startsWith("gradle")) {
                require(
                    command.environment["GRADLE_USER_HOME"]?.contains("{cache:") == true,
                ) { "GradleSharedUserCacheRequired" }
            }
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
        val resources = listOf(record.commonDirectory) + caches.values + plan.exclusiveResources.map {
            canonicalResource(record, it).toString()
        }
        val job = Path.of(record.commonDirectory).resolve("heartbeat-worktree-data").resolve("jobs").resolve(id)
        BuildExecution(
            id,
            resolved,
            checkoutDirectory(record, selected.directory).toString(),
            resources.distinct().sorted(),
            job.toString(),
            parentProcess = ProcessHandle.current().pid(),
            parentStartedAt = ProcessHandle.current().info().startInstant().orElse(null)?.toString(),
            configurationRevision = record.task.buildConfigurationRevision,
        )
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
        val root = Path.of(record.directory).toRealPath()
        val resolved = root.resolve(input).normalize()
        require(resolved.startsWith(root) && Files.isDirectory(resolved) && resolved.toRealPath().startsWith(root)) {
            "BuildDirectoryOutsideCheckout"
        }
        return resolved.toRealPath()
    }

    private fun cacheDirectory(record: WorktreeRecord, location: String): Path {
        val result = canonicalResource(record, location)
        val source = Path.of(record.sourceDirectory).toRealPath()
        val checkout = Path.of(record.directory).toRealPath()
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
        require(!result.startsWith(source.resolve("build")) && !result.startsWith(source.resolve(".gradle"))) {
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

    private suspend fun git(directory: String, vararg arguments: String): String =
        checkNotNull(process(directory, listOf("git", "--no-optional-locks") + arguments, optional = false))

    private suspend fun gitOptional(directory: String, vararg arguments: String): String? =
        process(directory, listOf("git", "--no-optional-locks") + arguments, optional = true)

    private suspend fun process(directory: String, arguments: List<String>, optional: Boolean): String? = withContext(
        dispatchers.io,
    ) {
        val output = Files.createTempFile("heartbeat-git-", ".out")
        var child: Process? = null
        try {
            child = ProcessBuilder(arguments).directory(Path.of(directory).toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start()
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
            stopNative(child)
            Files.deleteIfExists(output)
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
