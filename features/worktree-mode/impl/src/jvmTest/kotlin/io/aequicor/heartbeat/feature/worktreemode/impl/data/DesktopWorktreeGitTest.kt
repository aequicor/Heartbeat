package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCache
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWorktreeGitTest {
    @Test
    fun `foreground Java compiler output flag remains a supported generic command`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        try {
            val record = git.plan(root.toString(), "compiler-test").record()
            git.materialize(record)
            val plan = WorktreeBuildPlan(
                "javac",
                listOf(
                    WorktreeBuildCommand("compile", "javac", listOf("-cp", "{cache:classpath}", "-d", "build/classes")),
                ),
                listOf(WorktreeBuildCache("classpath", root.parent.resolve("compiler-cache").toString())),
            )
            git.validatePlan(record, plan)
        } finally {
            removeTestTree(root)
        }
    }

    @Test
    fun `committed source HEAD creates an isolated branch without dirty changes`() = runTest {
        val root = repository()
        Files.writeString(root.resolve("source.txt"), "dirty")
        val git = DesktopWorktreeGit(RealTestDispatchers)
        val plan = git.plan(root.toString(), "test-checkout")
        val record = plan.record()
        try {
            git.materialize(record)
            assertTrue(git.exists(record))
            assertEquals("committed", Files.readString(Path.of(record.directory).resolve("source.txt")))
            assertEquals("dirty", Files.readString(root.resolve("source.txt")))
            assertEquals("main", plan.sourceBranch)
            assertEquals("heartbeat/worktree-test-checkout", plan.branch)
            assertEquals(plan.baseCommit, command(root, "git", "rev-parse", "refs/heads/${plan.branch}"))
            git.materialize(record)
            assertEquals(plan.baseCommit, command(root, "git", "rev-parse", "HEAD"))
        } finally {
            removeTestTree(root)
        }
    }

    @Test
    fun `invalid shared caches and unused cache declarations fail closed`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        val record = git.plan(root.toString(), "cache-test").record()
        try {
            git.materialize(record)
            val base = WorktreeBuildPlan(
                "gradle",
                listOf(
                    WorktreeBuildCommand("test", "gradle", environment = mapOf("GRADLE_USER_HOME" to "{cache:gradle}")),
                ),
                listOf(WorktreeBuildCache("gradle", root.parent.resolve("shared-cache").toString())),
            )
            assertSecretEnvironmentRejected(git, record, base)
            assertFailsWith<IllegalArgumentException> { git.validatePlan(record, base.copy(caches = emptyList())) }
            assertFailsWith<IllegalArgumentException> {
                git.validatePlan(
                    record,
                    base.copy(commands = listOf(WorktreeBuildCommand("test", "gradle"))),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                git.validatePlan(
                    record,
                    base.copy(caches = listOf(WorktreeBuildCache("gradle", "build"))),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                git.validatePlan(
                    record,
                    base.copy(caches = listOf(WorktreeBuildCache("gradle", ".gradle"))),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                git.validatePlan(
                    record,
                    base.copy(commands = listOf(base.commands.single().copy(directory = ".."))),
                )
            }
            val approved = record.copy(task = record.task.copy(buildPlan = base, isBuildApproved = true))
            val execution = git.build(approved, "build-one", "test")
            assertTrue("--no-daemon" in execution.command.arguments)
            assertFalse(execution.command.environment.getValue("GRADLE_USER_HOME").contains("{cache:"))
            assertTrue(record.commonDirectory in execution.resources)
        } finally {
            removeTestTree(root)
        }
    }

    @Test
    fun `main checkout and isolated branches use identical repository resource`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        try {
            val isolated = git.plan(root.toString(), "shared-test").record()
            git.materialize(isolated)
            val main = git.trackMain(root.toString())
            assertEquals(isolated.commonDirectory, main.commonDirectory)
            assertEquals(listOf(isolated.commonDirectory), git.mergeLease(isolated, "merge-lease").resources)
            assertEquals(main.sourceDirectory, main.directory)
            assertTrue(git.exists(main.record().copy(task = main.record().task.copy(isIsolated = false))))
        } finally {
            removeTestTree(root)
        }
    }

    @Test
    fun `merge preflight rejects dirty source and verification requires committed reachable work`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        try {
            val record = git.plan(root.toString(), "merge-test").record()
            git.materialize(record)
            Files.writeString(root.resolve("source.txt"), "source dirty")
            val dirty = assertFailsWith<IllegalStateException> { git.mergePreflight(record) }
            assertEquals("OriginalCheckoutDirty", dirty.message)
            command(root, "git", "restore", "source.txt")
            val target = git.mergePreflight(record)
            val guarded = record.copy(mergeTargetCommit = target)
            val checkout = Path.of(record.directory)
            Files.writeString(checkout.resolve("source.txt"), "task change")
            assertFalse(git.verifyAction(guarded, merge = true, pullRequestUrl = null))
            command(checkout, "git", "add", "source.txt")
            command(checkout, "git", "commit", "-m", "task")
            assertFalse(git.verifyAction(guarded, merge = true, pullRequestUrl = null))
            command(root, "git", "merge", "--ff-only", checkNotNull(record.task.branch))
            assertTrue(git.verifyAction(guarded, merge = true, pullRequestUrl = null))
        } finally {
            removeTestTree(root)
        }
    }

    @Test
    fun `detached checkout pins only a known remote default for PR and never enables local merge`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        try {
            command(root, "git", "checkout", "--detach")
            val unknown = git.plan(root.toString(), "detached-unknown")
            assertEquals(null, unknown.sourceBranch)
            assertEquals(null, unknown.pullRequestBase)
            git.materialize(unknown.record())
            val missing = assertFailsWith<IllegalStateException> { git.actionPrompt(unknown.record(), merge = false) }
            assertEquals("PullRequestBaseUnavailable", missing.message)
            command(root, "git", "update-ref", "refs/remotes/origin/main", unknown.baseCommit)
            command(root, "git", "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
            val known = git.plan(root.toString(), "detached-known")
            assertEquals(null, known.sourceBranch)
            assertEquals("main", known.pullRequestBase)
            val record = known.record()
            git.materialize(record)
            assertTrue(git.actionPrompt(record, merge = false).contains("main"))
            val merge = assertFailsWith<IllegalStateException> { git.mergePreflight(record) }
            assertEquals("OriginalBranchUnavailable", merge.message)
        } finally {
            removeTestTree(root)
        }
    }
}

/** Dispatcher source injected into real JVM process tests, which must advance independently of virtual time. */
@Suppress("InjectDispatcher")
internal object RealTestDispatchers : DispatcherProvider {
    override val main = Dispatchers.Default
    override val default = Dispatchers.Default
    override val io = Dispatchers.IO
}

private fun WorktreeProvision.record(): WorktreeRecord = WorktreeRecord(
    WorktreeTask(
        "chat",
        WorkspaceRef("project"),
        sourceBranch = sourceBranch,
        branch = branch,
        baseCommit = baseCommit,
    ),
    sourceDirectory,
    directory,
    commonDirectory,
    Path.of(directory).fileName.toString(),
    pullRequestBase = pullRequestBase,
)

private fun repository(): Path {
    val directory = Files.createTempDirectory("heartbeat-git-test-").toRealPath()
    command(directory, "git", "init", "--initial-branch=main")
    command(directory, "git", "config", "user.name", "Worktree Test")
    command(directory, "git", "config", "user.email", "worktree-test@example.invalid")
    Files.writeString(directory.resolve("source.txt"), "committed")
    command(directory, "git", "add", "source.txt")
    command(directory, "git", "commit", "-m", "initial")
    return directory
}

private fun command(directory: Path, vararg arguments: String): String {
    val process = ProcessBuilder(arguments.toList()).directory(directory.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    check(process.waitFor() == 0) { "Test command failed" }
    return output
}

internal fun removeTestTree(root: Path) {
    val absolute = root.toAbsolutePath().normalize()
    check(absolute.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()))
    check(absolute.fileName.toString().startsWith("heartbeat-"))
    Files.walk(absolute).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(::removeTestPath)
    }
}

private fun removeTestPath(path: Path) {
    Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView::class.java)?.setReadOnly(false)
    var attempts = 0
    while (Files.exists(path)) {
        try {
            Files.deleteIfExists(path)
        } catch (error: java.nio.file.FileSystemException) {
            if (++attempts >= 200) throw error
            Thread.sleep(50L)
        }
    }
}

private suspend fun assertSecretEnvironmentRejected(
    git: DesktopWorktreeGit,
    record: WorktreeRecord,
    base: WorktreeBuildPlan,
) {
    val secretEnvironment = assertFailsWith<IllegalArgumentException> {
        git.validatePlan(
            record,
            base.copy(
                commands = listOf(
                    base.commands.single().copy(
                        environment = mapOf(
                            "GRADLE_USER_HOME" to "{cache:gradle}",
                            "TEST_ACCESS_TOKEN" to "synthetic-sentinel",
                        ),
                    ),
                ),
            ),
        )
    }
    assertEquals("SecretBuildEnvironment", secretEnvironment.message)
}
