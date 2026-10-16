package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCache
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.prepareSlot
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.releaseSnapshot
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.tryLockResource
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            assertEquals(record.sourceDirectory, target.repository)
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

    @Test
    fun `a linked slot cannot overwrite the original checkout`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        var link: Path? = null
        try {
            val record = sandbox(git, root)
            Files.writeString(Path.of(record.directory).resolve("source.txt"), "snapshot")
            val snapshot = git.snapshot(record, "operation-1")
            val target = checkNotNull(git.build(record, "operation-1", "compile").sandbox)
                .copy(snapshot = snapshot, slot = 0)
            val slot = slotDirectory(target.pool, 0)
            Files.createDirectories(slot.parent)
            linkDirectory(slot, root)
            link = slot
            Files.writeString(root.resolve("source.txt"), "unsaved source")
            Files.writeString(root.resolve("untracked.txt"), "unsaved file")
            val head = command(root, "git", "rev-parse", "HEAD")

            assertFailsWith<IllegalStateException> { prepareSlot(target) }

            assertEquals("unsaved source", Files.readString(root.resolve("source.txt")))
            assertEquals("unsaved file", Files.readString(root.resolve("untracked.txt")))
            assertEquals(head, command(root, "git", "rev-parse", "HEAD"))
            assertEquals(root, slot.toRealPath())
        } finally {
            link?.let(Files::deleteIfExists)
            removeTestTree(root)
        }
    }

    @Test
    fun `a slot cannot reuse Git metadata belonging to another checkout`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        try {
            val record = sandbox(git, root)
            val checkout = Path.of(record.directory)
            Files.writeString(checkout.resolve("source.txt"), "snapshot")
            val snapshot = git.snapshot(record, "operation-1")
            val target = checkNotNull(git.build(record, "operation-1", "compile").sandbox)
                .copy(snapshot = snapshot, slot = 0)
            val slot = slotDirectory(target.pool, 0)
            Files.createDirectories(slot)
            Files.copy(checkout.resolve(".git"), slot.resolve(".git"))
            Files.writeString(slot.resolve("untracked.txt"), "keep until ownership is known")
            val head = command(checkout, "git", "rev-parse", "HEAD")
            val index = command(checkout, "git", "write-tree")

            assertFailsWith<IllegalStateException> { prepareSlot(target) }
            assertFailsWith<IllegalStateException> { git.sweepSlots(record, keep = 0) }

            assertEquals(head, command(checkout, "git", "rev-parse", "HEAD"))
            assertEquals(index, command(checkout, "git", "write-tree"))
            assertEquals("snapshot", Files.readString(checkout.resolve("source.txt")))
            assertTrue(Files.exists(slot.resolve("untracked.txt")))
        } finally {
            removeTestTree(root)
        }
    }

    @Test
    fun `sweeping a linked slot cannot remove another checkout`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        var link: Path? = null
        try {
            val record = sandbox(git, root)
            val checkout = Path.of(record.directory)
            val target = checkNotNull(git.build(record, "operation-1", "compile").sandbox)
            val slot = slotDirectory(target.pool, 0)
            Files.createDirectories(slot.parent)
            linkDirectory(slot, checkout)
            link = slot
            Files.writeString(checkout.resolve("untracked.txt"), "unsaved work")

            assertFailsWith<IllegalStateException> { git.sweepSlots(record, keep = 0) }

            assertEquals("unsaved work", Files.readString(checkout.resolve("untracked.txt")))
            assertTrue(record.directory in command(root, "git", "worktree", "list", "--porcelain"))
            assertEquals(checkout, slot.toRealPath())
        } finally {
            link?.let(Files::deleteIfExists)
            removeTestTree(root)
        }
    }

    @Test
    fun `reclaiming a sandbox removes ignored outputs and keeps the task's work`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        try {
            val record = sandbox(git, root)
            val checkout = Path.of(record.directory)
            Files.writeString(checkout.resolve("source.txt"), "edited")
            Files.writeString(checkout.resolve("added.txt"), "new")
            Files.createDirectories(checkout.resolve("build"))
            Files.writeString(checkout.resolve("build/output.bin"), "artifact")

            git.reclaim(record)

            assertFalse(Files.exists(checkout.resolve("build")))
            assertEquals("edited", Files.readString(checkout.resolve("source.txt")))
            assertEquals("new", Files.readString(checkout.resolve("added.txt")))
        } finally {
            removeTestTree(root)
        }
    }

    @Test
    fun `stale unowned snapshot refs are pruned while active and recent ones stay`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        try {
            val record = sandbox(git, root)
            git.snapshot(record, "active")
            git.snapshot(record, "recent")
            val tree = command(root, "git", "rev-parse", "HEAD^{tree}")
            val old = command(
                root,
                "git",
                "commit-tree",
                tree,
                "-m",
                "old",
                environment = mapOf("GIT_COMMITTER_DATE" to "2000-01-01T00:00:00+0000"),
            )
            command(root, "git", "update-ref", "refs/heartbeat/snapshots/stale", old)
            command(root, "git", "update-ref", "refs/heartbeat/snapshots/stale-active", old)

            git.pruneSnapshots(record, active = setOf("active", "stale-active"))

            val refs = command(root, "git", "for-each-ref", "--format=%(refname)", "refs/heartbeat/snapshots/")
            assertEquals(
                setOf("active", "recent", "stale-active").map { "refs/heartbeat/snapshots/$it" }.toSet(),
                refs.lines().toSet(),
            )
            git.discardSnapshot(record, "recent")
            assertFalse("snapshots/recent" in command(root, "git", "show-ref"))
        } finally {
            removeTestTree(root)
        }
    }

    @Test
    fun `a slot can still be created after the sandbox checkout is removed`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        try {
            val record = sandbox(git, root)
            Files.writeString(Path.of(record.directory).resolve("source.txt"), "kept in the snapshot")
            val snapshot = git.snapshot(record, "operation-1")
            val target = checkNotNull(git.build(record, "operation-1", "compile").sandbox)
                .copy(snapshot = snapshot, slot = 0)
            command(root, "git", "worktree", "remove", "--force", record.directory)

            prepareSlot(target)

            val slot = slotDirectory(target.pool, 0)
            assertEquals("kept in the snapshot", Files.readString(slot.resolve("source.txt")))
        } finally {
            removeTestTree(root)
        }
    }

    @Test
    fun `sweeping removes surplus free slots with their registrations and skips a locked one`() = runTest {
        val root = repository()
        val git = DesktopWorktreeGit(RealTestDispatchers)
        try {
            val record = sandbox(git, root)
            val snapshot = git.snapshot(record, "operation-1")
            val target = checkNotNull(git.build(record, "operation-1", "compile").sandbox).copy(snapshot = snapshot)
            (0..3).forEach { prepareSlot(target.copy(slot = it)) }
            val slots = (0..3).map { slotDirectory(target.pool, it) }
            removeTestPathTree(slots[3])
            val held = checkNotNull(tryLockResource(slots[2].toString()))
            try {
                assertTrue(git.sweepSlots(record, keep = 1))
            } finally {
                held.close()
            }

            assertTrue(Files.isDirectory(slots[0]))
            assertFalse(Files.exists(slots[1]))
            assertTrue(Files.isDirectory(slots[2]))
            val registered = command(root, "git", "worktree", "list", "--porcelain")
            assertTrue("slot-0" in registered && "slot-2" in registered, registered)
            assertFalse("slot-1" in registered || "slot-3" in registered, registered)
            assertFalse(git.sweepSlots(record, keep = 1))
            assertFalse(Files.exists(slots[2]))
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

    private fun linkDirectory(path: Path, target: Path) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", path.toString(), target.toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { output }
        } else {
            Files.createSymbolicLink(path, target)
        }
    }

    private fun removeTestPathTree(path: Path) {
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
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

    private fun command(
        directory: Path,
        vararg arguments: String,
        environment: Map<String, String> = emptyMap(),
    ): String {
        val process = ProcessBuilder(arguments.toList()).directory(directory.toFile()).redirectErrorStream(true)
            .apply { environment().putAll(environment) }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { "Test command failed: $output" }
        return output
    }
}
