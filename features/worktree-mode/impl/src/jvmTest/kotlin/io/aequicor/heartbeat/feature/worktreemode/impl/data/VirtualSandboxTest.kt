package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualFileChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.prepareSlot
import io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.restoreSlot
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class VirtualSandboxTest {
    @Test
    fun `old child results remain integrable after rebasing both workspaces`() = runTest {
        val f = VirtualFixture()
        try {
            val parent = f.create("rebase-parent")
            val files = assertNotNull(f.backend.find(parent))
            files.change(0, listOf(VirtualFileChange("parent.txt", "parent")))
            val child = f.create("rebase-child", parent)
            assertNotNull(f.backend.find(child)).change(0, listOf(VirtualFileChange("child.txt", "child")))
            val oldResult = f.backend.state(child).snapshot
            Files.writeString(f.project.resolve("upstream"), "external")
            f.backend.inspect(parent)
            f.backend.inspect(child)
            f.cmd("add", ".")
            f.cmd("commit", "-m", "external")
            f.backend.resume(parent, false)
            f.backend.resume(child, false)
            f.backend.integrate(parent, child, expectedSnapshot = oldResult)
            assertEquals("external", files.read("upstream"))
            assertEquals("child", files.read("child.txt"))
            assertEquals("parent", files.read("parent.txt"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `reset followed by commit between observations still requires manual continuation`() = runTest {
        val f = VirtualFixture()
        try {
            val ref = f.create("reset")
            f.cmd("reset", "--hard", "HEAD")
            assertFailsWith<IllegalStateException> { f.backend.handoff(f.record("reset")) }
            Files.writeString(f.project.resolve("after-reset"), "new commit")
            f.cmd("add", ".")
            f.cmd("commit", "-m", "after reset")
            assertEquals(VirtualSandboxPhase.Pausing, f.backend.inspect(ref).phase)
            f.backend = f.restart()
            assertEquals(VirtualSandboxPhase.Manual, f.backend.resume(ref, false).phase)
            assertEquals(VirtualSandboxPhase.Ready, f.backend.resume(ref, true).phase)
        } finally {
            f.close()
        }
    }

    @Test
    fun `virtual reads and merge cannot execute repository fsmonitor or custom merge drivers`() = runTest {
        val f = VirtualFixture()
        try {
            Files.writeString(f.project.resolve(".gitattributes"), "source.txt merge=marker\n")
            f.cmd("add", ".")
            f.cmd("commit", "-m", "attributes")
            val ref = f.create("drivers")
            assertNotNull(f.backend.find(ref)).change(0, listOf(VirtualFileChange("source.txt", "agent\n")))
            Files.writeString(f.project.resolve("source.txt"), "external\n")
            f.backend.inspect(ref)
            f.cmd("add", ".")
            f.cmd("commit", "-m", "external")
            val marker = f.project.resolve(".git/driver-ran")
            val monitorMarker = f.project.resolve(".git/monitor-ran")
            val script = f.project.resolve(".git/monitor.sh")
            Files.writeString(script, "#!/bin/sh\nprintf executed > ${shellQuote(monitorMarker)}\nprintf 'token\\0'\n")
            script.toFile().setExecutable(true)
            f.cmd("config", "core.fsmonitor", shellQuote(script))
            f.cmd("config", "merge.marker.driver", "printf executed > ${shellQuote(marker)}; exit 1")
            val result = f.backend.resume(ref, false)
            assertEquals(VirtualSandboxPhase.Conflicted, result.phase)
            assertFalse(Files.exists(marker))
            assertFalse(Files.exists(monitorMarker))
        } finally {
            f.close()
        }
    }

    @Test
    fun `overlays isolate durable edits and never create source checkouts or branches`() = runTest {
        val f = VirtualFixture()
        try {
            val first = f.create("first")
            val second = f.create("second")
            val files = assertNotNull(f.backend.find(first))
            files.change(
                0,
                listOf(VirtualFileChange("source.txt", "agent"), VirtualFileChange("new/code.go", "package code")),
            )
            assertEquals("agent", files.read("source.txt"))
            assertEquals("base\n", assertNotNull(f.backend.find(second)).read("source.txt"))
            assertEquals("base\n", Files.readString(f.project.resolve("source.txt")))
            assertEquals("", f.cmd("status", "--porcelain"))
            assertEquals(1, f.cmd("worktree", "list", "--porcelain").lines().count { it.startsWith("worktree ") })
            assertFalse(Files.exists(Path.of(f.record("first").directory)))
            val restarted = f.restart()
            val restored = assertNotNull(restarted.find(first))
            assertEquals("package code", restored.read("new/code.go"))
            assertEquals(1, restored.revision())
            restored.change(1, listOf(VirtualFileChange("source.txt", null), VirtualFileChange("renamed.txt", "agent")))
            assertNull(restored.read("source.txt"))
            assertEquals("agent", restored.read("renamed.txt"))
            assertFailsWith<IllegalStateException> { restored.change(1, listOf(VirtualFileChange("x", "stale"))) }
            assertFailsWith<IllegalArgumentException> { restored.read("../outside") }
            assertFailsWith<IllegalArgumentException> {
                restored.change(
                    2,
                    listOf(VirtualFileChange(".git/config", "bad")),
                )
            }
        } finally {
            f.close()
        }
    }

    @Test
    fun `moving binary blobs links and executables preserves Git entries and deletion needs no text read`() = runTest {
        val f = VirtualFixture()
        try {
            Files.write(f.project.resolve("binary"), byteArrayOf(0, 1, -1))
            Files.writeString(f.project.resolve("tool"), "#!/bin/sh\n")
            f.cmd("add", ".")
            f.cmd("update-index", "--chmod=+x", "tool")
            f.cmd("commit", "-m", "modes")
            val ref = f.create("modes")
            val files = assertNotNull(f.backend.find(ref))
            files.move(0, "binary", "moved.bin")
            files.move(1, "tool", "tools/run")
            assertNull(files.read("binary"))
            assertTrue("moved.bin" in files.paths())
            val snapshot = f.backend.state(ref).snapshot
            assertTrue(f.cmd("ls-tree", snapshot, "tools/run").startsWith("100755 blob"))
            val seen = mutableListOf<String>()
            files.scanTextFiles(files.paths()) { path, _ ->
                seen += path
                true
            }
            assertTrue("tools/run" in seen)
            assertFalse("moved.bin" in seen)
            files.change(2, listOf(VirtualFileChange("moved.bin", null)))
            assertFalse("moved.bin" in files.paths())
        } finally {
            f.close()
        }
    }

    @Test
    fun `external edits revoke an active conflict resolver without losing source versions`() = runTest {
        val f = VirtualFixture()
        try {
            val ref = f.create("resolver")
            val files = assertNotNull(f.backend.find(ref))
            files.change(0, listOf(VirtualFileChange("source.txt", "agent\n")))
            Files.writeString(f.project.resolve("source.txt"), "external\n")
            f.backend.inspect(ref)
            f.cmd("add", ".")
            f.cmd("commit", "-m", "external")
            f.backend.resume(ref, false)
            assertTrue(f.backend.resume(ref, true, true).isResolving)
            Files.writeString(f.project.resolve("second.txt"), "new edit")
            assertEquals(VirtualSandboxPhase.Pausing, f.backend.inspect(ref).phase)
            assertFailsWith<IllegalStateException> { f.backend.resolve(ref, "source.txt", "stale") }
            f.cmd("add", ".")
            f.cmd("commit", "-m", "second external")
            val paused = f.backend.resume(ref, false)
            assertFalse(paused.isResolving)
            assertEquals(listOf("source.txt"), paused.conflicts)
            f.backend.resume(ref, true, true)
            f.backend.resolve(ref, "source.txt", "resolved\n")
            assertEquals("new edit", files.read("second.txt"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `dirty projects reject creation and ignored build outputs do not`() = runTest {
        val f = VirtualFixture()
        try {
            Files.writeString(f.project.resolve("dirty.txt"), "external")
            assertFailsWith<IllegalStateException> { f.create("dirty") }
            Files.delete(f.project.resolve("dirty.txt"))
            Files.createDirectories(f.project.resolve("build"))
            Files.writeString(f.project.resolve("build/output"), "ignored")
            f.create("clean")
            Files.writeString(f.project.resolve("source.txt"), "external")
            assertFailsWith<IllegalStateException> { f.create("unstaged") }
            f.cmd("add", "source.txt")
            assertFailsWith<IllegalStateException> { f.create("staged") }
        } finally {
            f.close()
        }
    }

    @Test
    fun `source edits pause then clean descendant rebases without changing original files`() = runTest {
        val f = VirtualFixture()
        try {
            val ref = f.create("rebase")
            val files = assertNotNull(f.backend.find(ref))
            files.change(0, listOf(VirtualFileChange("source.txt", "agent")))
            Files.writeString(f.project.resolve("upstream.txt"), "new base")
            assertEquals(VirtualSandboxPhase.Pausing, f.backend.inspect(ref).phase)
            assertFailsWith<IllegalStateException> { files.read("source.txt") }
            f.cmd("add", "upstream.txt")
            f.cmd("commit", "-m", "external")
            val state = f.backend.resume(ref, explicit = false)
            assertEquals(VirtualSandboxPhase.Ready, state.phase)
            assertEquals(f.cmd("rev-parse", "HEAD"), state.baseCommit)
            assertEquals("agent", files.read("source.txt"))
            assertEquals("new base", files.read("upstream.txt"))
            assertEquals("base\n", Files.readString(f.project.resolve("source.txt")))
        } finally {
            f.close()
        }
    }

    @Test
    fun `timeout persists across restart and a late commit requires explicit continuation`() = runTest {
        val f = VirtualFixture()
        try {
            val ref = f.create("timeout")
            Files.writeString(f.project.resolve("source.txt"), "external")
            val deadline = assertNotNull(f.backend.inspect(ref).deadlineMillis)
            f.now = deadline
            f.backend = f.restart()
            f.cmd("add", "source.txt")
            f.cmd("commit", "-m", "late")
            assertEquals(VirtualSandboxPhase.Manual, f.backend.resume(ref, explicit = false).phase)
            assertEquals(VirtualSandboxPhase.Ready, f.backend.resume(ref, explicit = true).phase)
        } finally {
            f.close()
        }
    }

    @Test
    fun `conflicts retain the old view and require explicit resolution authorization`() = runTest {
        val f = VirtualFixture()
        try {
            val ref = f.create("conflict")
            val files = assertNotNull(f.backend.find(ref))
            files.change(0, listOf(VirtualFileChange("source.txt", "agent\n")))
            val old = f.backend.state(ref).snapshot
            Files.writeString(f.project.resolve("source.txt"), "external\n")
            f.backend.inspect(ref)
            f.cmd("add", "source.txt")
            f.cmd("commit", "-m", "external conflict")
            val conflict = f.backend.resume(ref, explicit = false)
            assertEquals(VirtualSandboxPhase.Conflicted, conflict.phase)
            assertEquals(listOf("source.txt"), conflict.conflicts)
            assertEquals(old, conflict.snapshot)
            assertFailsWith<IllegalStateException> { f.backend.resolve(ref, "source.txt", "resolved\n") }
            f.backend.resume(ref, explicit = true, resolveConflicts = true)
            assertTrue(assertNotNull(files.read("source.txt")).contains("<<<<<<<"))
            assertEquals(VirtualSandboxPhase.Ready, f.backend.resolve(ref, "source.txt", "resolved\n").phase)
            assertEquals("resolved\n", files.read("source.txt"))
            assertEquals("external\n", Files.readString(f.project.resolve("source.txt")))
        } finally {
            f.close()
        }
    }

    @Test
    fun `children inherit a parent snapshot and integrate against that fork`() = runTest {
        val f = VirtualFixture()
        try {
            val parent = f.create("parent")
            val files = assertNotNull(f.backend.find(parent))
            files.change(0, listOf(VirtualFileChange("inherited.py", "parent = True")))
            val child = f.create("child", parent)
            val childFiles = assertNotNull(f.backend.find(child))
            assertEquals("parent = True", childFiles.read("inherited.py"))
            childFiles.change(0, listOf(VirtualFileChange("child.rs", "fn child() {}")))
            files.change(1, listOf(VirtualFileChange("parent.java", "class Parent {}")))
            f.backend.integrate(parent, child)
            assertEquals("fn child() {}", files.read("child.rs"))
            assertEquals("class Parent {}", files.read("parent.java"))
            assertNull(childFiles.read("parent.java"))
            assertFailsWith<IllegalStateException> { f.backend.integrate(f.create("unrelated"), child) }
        } finally {
            f.close()
        }
    }

    @Test
    fun `handoff is idempotent freezes editing and never switches the source checkout`() = runTest {
        val f = VirtualFixture()
        try {
            val ref = f.create("handoff")
            val files = assertNotNull(f.backend.find(ref))
            files.change(0, listOf(VirtualFileChange("source.txt", "ready")))
            val head = f.cmd("rev-parse", "HEAD")
            val result = f.backend.handoff(f.record("handoff"))
            assertEquals(result, f.backend.handoff(f.record("handoff")))
            assertEquals(result.resultCommit, f.cmd("rev-parse", "heartbeat/worktree-handoff"))
            assertEquals(head, f.cmd("rev-parse", "HEAD"))
            assertEquals("main", f.cmd("branch", "--show-current"))
            assertEquals("", f.cmd("status", "--porcelain"))
            assertFailsWith<IllegalStateException> { files.change(1, listOf(VirtualFileChange("x", "late"))) }
            val occupied = f.create("occupied")
            assertNotNull(f.backend.find(occupied)).change(0, listOf(VirtualFileChange("x", "new")))
            f.cmd("branch", "heartbeat/worktree-occupied")
            assertFailsWith<IllegalStateException> { f.backend.handoff(f.record("occupied")) }
        } finally {
            f.close()
        }
    }

    @Test
    fun `arbitrary ecosystems need no caches and slots roll back while preserving warm outputs`() = runTest {
        val f = VirtualFixture()
        try {
            val ref = f.create("build")
            val files = assertNotNull(f.backend.find(ref))
            files.change(0, listOf(VirtualFileChange("source.txt", "snapshot")))
            val host = DesktopWorktreeGit(RealTestDispatchers, lazy { f.backend })
            for ((system, executable) in listOf(
                "go" to "go",
                "python" to "python",
                "java" to "javac",
                "kotlin" to "kotlinc",
                "rust" to "cargo",
                "custom" to "custom-check",
            )) {
                val plan = WorktreeBuildPlan(system, listOf(WorktreeBuildCommand("check", executable)))
                host.validatePlan(f.record("build"), plan)
            }
            val record = f.record("build").let {
                it.copy(
                    task = it.task.copy(
                        buildPlan = WorktreeBuildPlan(
                            "custom",
                            listOf(WorktreeBuildCommand("check", "custom-check")),
                        ),
                    ),
                )
            }
            val execution = host.build(record, "test-build", "check")
            val target = assertNotNull(execution.sandbox).copy(slot = 0)
            prepareSlot(target)
            val slot = slotDirectory(target.pool, 0)
            assertEquals("snapshot", Files.readString(slot.resolve("source.txt")))
            Files.createDirectories(slot.resolve("build"))
            Files.writeString(slot.resolve("build/cache"), "warm")
            Files.writeString(slot.resolve("source.txt"), "generated change")
            Files.writeString(slot.resolve("stray"), "generated")
            val nested = Files.createDirectory(slot.resolve("nested-repository"))
            val nestedGit = ProcessBuilder("git", "init", nested.toString()).redirectErrorStream(true).start()
            nestedGit.inputStream.readAllBytes()
            check(nestedGit.waitFor() == 0)
            restoreSlot(target)
            assertFalse(Files.exists(nested))
            assertEquals("base\n", Files.readString(slot.resolve("source.txt")))
            assertFalse(Files.exists(slot.resolve("stray")))
            prepareSlot(target)
            assertEquals("warm", Files.readString(slot.resolve("build/cache")))
            assertEquals("snapshot", Files.readString(slot.resolve("source.txt")))
            restoreSlot(target)
        } finally {
            f.close()
        }
    }
}

private class VirtualFixture {
    private val root = Files.createTempDirectory("heartbeat-virtual-test-").toRealPath()
    val project: Path = Files.createDirectory(root.resolve("project"))
    private val stores = SettingsStores(root.resolve("state"))
    private val git = VirtualSandboxGit(RealTestDispatchers)
    private val settings = StoredProjectBuildSettings(lazy { stores })
    var now = 1_000L
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(now)
    }
    var backend = restart()

    init {
        cmd("init", "--initial-branch=main")
        cmd("config", "core.autocrlf", "false")
        cmd("config", "user.name", "Sandbox Test")
        cmd("config", "user.email", "sandbox@test.invalid")
        Files.writeString(project.resolve("source.txt"), "base\n")
        Files.writeString(project.resolve(".gitignore"), "build/\n")
        cmd("add", ".")
        cmd("commit", "-m", "base")
    }

    fun restart() = DesktopVirtualWorkspaces(stores, git, settings, RealTestDispatchers, clock)
    suspend fun create(id: String, parent: WorkspaceRef? = null): WorkspaceRef = backend.create(record(id, parent))
    fun record(id: String, parent: WorkspaceRef? = null): WorktreeRecord {
        val common = project.resolve(".git").toRealPath()
        val base = cmd("rev-parse", "HEAD")
        return WorktreeRecord(
            WorktreeTask(
                id,
                WorkspaceRef("project"),
                branch = "heartbeat/worktree-$id",
                baseCommit = base,
                isSandbox = true,
                virtualSandbox = VirtualSandboxState(baseCommit = base, snapshot = base),
            ),
            project.toString(),
            common.resolve("heartbeat-worktree-data/checkouts/$id").toString(),
            common.toString(),
            id,
            parentWorkspace = parent,
        )
    }

    fun cmd(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(project.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output.trim()
    }
    fun close() = removeTestTree(root)
}

private fun shellQuote(path: Path): String = "'" + path.toString().replace('\\', '/').replace("'", "'\\''") + "'"
