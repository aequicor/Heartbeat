package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCache
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.prepareSlot
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.releaseSnapshot
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SandboxBuildTest {
    @Test
    fun `snapshot captures working files without ignored outputs or touching the agent index`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        try {
            val record = sandbox(git, root)
            val checkout = Path.of(record.directory)
            Files.writeString(checkout.resolve("source.txt"), "edited")
            Files.writeString(checkout.resolve("added.txt"), "new")
            Files.createDirectories(checkout.resolve("build"))
            Files.writeString(checkout.resolve("build/output.bin"), "artifact")

            val snapshot = git.snapshot(record, "operation-1")

            assertEquals("edited", command(root, "git", "show", "$snapshot:source.txt"))
            assertEquals("new", command(root, "git", "show", "$snapshot:added.txt"))
            assertFalse("build/output.bin" in command(root, "git", "ls-tree", "-r", "--name-only", snapshot))
            assertEquals(snapshot, command(root, "git", "rev-parse", "refs/heartbeat/snapshots/operation-1"))
            assertEquals(record.task.baseCommit, command(checkout, "git", "rev-parse", "HEAD"))
            assertEquals("", command(checkout, "git", "diff", "--cached", "--name-only"))
        } finally {
            removeTestTree(root)
        }
    }

    @Test
    fun `sandbox builds target a slot and lock only exclusive resources`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        try {
            val record = sandbox(git, root)
            val execution = git.build(record, "operation-1", "compile")
            val target = assertNotNull(execution.sandbox)
            assertFalse(record.commonDirectory in execution.resources)
            assertEquals(record.directory, target.repository)
            assertEquals(record.provisioning, target.affinity)
            assertEquals(".", target.relativeDirectory)

            val placed = execution.copy(sandbox = target.copy(snapshot = "abc")).inSlot(1)
            val slot = slotDirectory(target.pool, 1)
            assertEquals(slot.toString(), placed.directory)
            assertTrue(slot.toString() in placed.resources)
            assertEquals(1, placed.sandbox?.slot)
        } finally {
            removeTestTree(root)
        }
    }

    @Test
    fun `slot follows each snapshot and keeps ignored outputs for incremental builds`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        try {
            val record = sandbox(git, root)
            val checkout = Path.of(record.directory)
            Files.writeString(checkout.resolve("source.txt"), "first")
            val first = git.snapshot(record, "operation-1")
            val target = checkNotNull(git.build(record, "operation-1", "compile").sandbox)
                .copy(snapshot = first, slot = 0)

            prepareSlot(target)
            val slot = slotDirectory(target.pool, 0)
            assertEquals("first", Files.readString(slot.resolve("source.txt")))
            Files.createDirectories(slot.resolve("build"))
            Files.writeString(slot.resolve("build/cache.bin"), "warm")
            Files.writeString(slot.resolve("stray.txt"), "untracked")
            releaseSnapshot(target, "operation-1")
            assertFalse("refs/heartbeat/snapshots/operation-1" in command(root, "git", "show-ref"))

            Files.writeString(checkout.resolve("source.txt"), "second")
            val second = git.snapshot(record, "operation-2")
            prepareSlot(target.copy(snapshot = second))

            assertEquals("second", Files.readString(slot.resolve("source.txt")))
            assertEquals("warm", Files.readString(slot.resolve("build/cache.bin")))
            assertFalse(Files.exists(slot.resolve("stray.txt")))
            assertEquals(second, command(slot, "git", "rev-parse", "HEAD"))
        } finally {
            removeTestTree(root)
        }
    }

    private suspend fun sandbox(git: DesktopWorktreeGit, root: Path): WorktreeRecord {
        val plan = git.plan(root.toString(), "sandbox-test")
        val record = WorktreeRecord(
            WorktreeTask(
                "chat",
                WorkspaceRef("project"),
                sourceBranch = plan.sourceBranch,
                branch = plan.branch,
                baseCommit = plan.baseCommit,
                isSandbox = true,
            ),
            plan.sourceDirectory,
            plan.directory,
            plan.commonDirectory,
            "sandbox-test",
        )
        git.materialize(record)
        val build = WorktreeBuildPlan(
            "javac",
            listOf(WorktreeBuildCommand("compile", "javac", listOf("-cp", "{cache:classpath}", "-d", "build"))),
            listOf(WorktreeBuildCache("classpath", root.parent.resolve("${root.fileName}-cache").toString())),
        )
        return record.copy(task = record.task.copy(buildPlan = build, isBuildApproved = true))
    }

    private fun repository(): Path {
        val directory = Files.createTempDirectory("heartbeat-sandbox-test-").toRealPath()
        command(directory, "git", "init", "--initial-branch=main")
        command(directory, "git", "config", "user.name", "Sandbox Test")
        command(directory, "git", "config", "user.email", "sandbox-test@example.invalid")
        Files.writeString(directory.resolve("source.txt"), "committed")
        Files.writeString(directory.resolve(".gitignore"), "build/\n")
        command(directory, "git", "add", "source.txt", ".gitignore")
        command(directory, "git", "commit", "-m", "initial")
        return directory
    }

    private fun command(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(arguments.toList()).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { "Test command failed: $output" }
        return output
    }
}
