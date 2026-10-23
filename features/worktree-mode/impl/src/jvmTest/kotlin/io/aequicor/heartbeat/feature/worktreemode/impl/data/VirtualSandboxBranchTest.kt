package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualFileChange
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sandbox operations on branches of the real repository: commits, pushes, loads, merges and history. */
class VirtualSandboxBranchTest {
    @Test
    fun `commit to branch creates and fast-forwards a branch without touching the source checkout`() = runTest {
        val f = VirtualFixture()
        try {
            val ref = f.create("commit")
            val files = assertNotNull(f.backend.find(ref))
            files.change(0, listOf(VirtualFileChange("source.txt", "first")))
            val head = f.cmd("rev-parse", "HEAD")
            val first = f.backend.commitToBranch(ref, "feature/from-sandbox", "feat: first")
            assertEquals(first, f.cmd("rev-parse", "feature/from-sandbox"))
            assertEquals(head, f.cmd("rev-parse", "feature/from-sandbox^"))
            assertEquals("first", f.cmd("show", "feature/from-sandbox:source.txt"))
            assertEquals("main", f.cmd("branch", "--show-current"))
            assertEquals("", f.cmd("status", "--porcelain"))
            files.change(1, listOf(VirtualFileChange("source.txt", "second")))
            val second = f.backend.commitToBranch(ref, "feature/from-sandbox", "feat: second")
            assertEquals(first, f.cmd("rev-parse", "$second^"))
            assertEquals("second", f.cmd("show", "feature/from-sandbox:source.txt"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `checkout replaces the sandbox files with another branch and commits continue it`() = runTest {
        val f = VirtualFixture()
        try {
            f.cmd("switch", "-c", "pr/work")
            Files.writeString(f.project.resolve("pr.txt"), "from pr")
            f.cmd("add", "pr.txt")
            f.cmd("commit", "-m", "pr work")
            val tip = f.cmd("rev-parse", "HEAD")
            f.cmd("switch", "main")
            val ref = f.create("checkout")
            val files = assertNotNull(f.backend.find(ref))
            assertEquals(null, files.read("pr.txt"))
            f.backend.checkoutRef(ref, "pr/work", isFetch = false)
            assertEquals("from pr", files.read("pr.txt"))
            val commit = f.backend.commitToBranch(ref, "pr/work", "feat: continue")
            assertEquals(tip, f.cmd("rev-parse", "$commit^"))
            assertFailsWith<IllegalStateException> { f.backend.checkoutRef(ref, "missing", isFetch = false) }
            val remote = Files.createTempDirectory("heartbeat-virtual-remote-")
            f.cmd("init", "--bare", remote.toString())
            f.cmd("remote", "add", "origin", remote.toString())
            val pushed = f.backend.commitToBranch(ref, "pr/work", "feat: push", isPush = true)
            assertEquals(pushed, f.cmd("--git-dir=$remote", "rev-parse", "pr/work"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `merge brings the base branch into a checked out branch and commits it as a merge`() = runTest {
        val f = VirtualFixture()
        try {
            f.cmd("switch", "-c", "pr/merge")
            Files.writeString(f.project.resolve("pr.txt"), "from pr")
            f.cmd("add", "pr.txt")
            f.cmd("commit", "-m", "pr work")
            val tip = f.cmd("rev-parse", "HEAD")
            f.cmd("switch", "main")
            Files.writeString(f.project.resolve("main.txt"), "from main")
            f.cmd("add", "main.txt")
            f.cmd("commit", "-m", "main work")
            val main = f.cmd("rev-parse", "HEAD")
            val ref = f.create("merge")
            val files = assertNotNull(f.backend.find(ref))
            f.backend.checkoutRef(ref, "pr/merge", isFetch = false)
            assertEquals(null, files.read("main.txt"))
            assertTrue("pr work" in f.backend.history(ref, "pr/merge", "main", isStat = true))
            val merged = f.backend.mergeRef(ref, "main", isFetch = false)
            assertTrue(merged.conflicts.isEmpty())
            assertEquals("from main", files.read("main.txt"))
            assertEquals("from pr", files.read("pr.txt"))
            assertEquals(merged, f.backend.mergeRef(ref, "main", isFetch = false))
            val commit = f.backend.commitToBranch(ref, "pr/merge", "Merge main")
            assertEquals("$tip $main", f.cmd("show", "-s", "--format=%P", commit))
            assertEquals(
                f.cmd("config", "user.email") + " " + f.cmd("config", "user.email"),
                f.cmd("show", "-s", "--format=%ae %ce", commit),
            )
            assertEquals("main", f.cmd("branch", "--show-current"))
            assertEquals("", f.cmd("status", "--porcelain"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `a pushed commit is split from an earlier commit and force pushed with a lease`() = runTest {
        val f = VirtualFixture()
        val remote = Files.createTempDirectory("heartbeat-virtual-remote-")
        try {
            f.cmd("init", "--bare", remote.toString())
            f.cmd("remote", "add", "origin", remote.toString())
            f.cmd("switch", "-c", "pr/split")
            val start = f.cmd("rev-parse", "HEAD")
            Files.createDirectories(f.project.resolve("one"))
            Files.writeString(f.project.resolve("one/a.txt"), "a")
            Files.writeString(f.project.resolve("b.txt"), "b")
            f.cmd("add", "one/a.txt", "b.txt")
            f.cmd("commit", "-m", "too big")
            val big = f.cmd("rev-parse", "HEAD")
            f.cmd("push", "origin", "pr/split")
            f.cmd("switch", "main")
            val ref = f.create("split")
            val files = assertNotNull(f.backend.find(ref))
            f.backend.checkoutRef(ref, "pr/split", isFetch = true, at = start.take(12))
            assertEquals(null, files.read("b.txt"))
            f.backend.restorePaths(ref, listOf("one"), big)
            assertEquals("a", files.read("one/a.txt"))
            assertFailsWith<IllegalStateException> { f.backend.commitToBranch(ref, "pr/split", "feat: a") }
            val first = f.backend.commitToBranch(ref, "pr/split", "feat: a", isForce = true)
            f.backend.restorePaths(ref, listOf("b.txt"), "pr/split")
            assertEquals("a", files.read("one/a.txt"))
            f.backend.restorePaths(ref, listOf("b.txt"), big)
            val second = f.backend.commitToBranch(ref, "pr/split", "feat: b", isPush = true, isForce = true)
            assertEquals(second, f.cmd("--git-dir=$remote", "rev-parse", "pr/split"))
            assertEquals(first, f.cmd("rev-parse", "$second^"))
            assertEquals(start, f.cmd("rev-parse", "$first^"))
            assertEquals("", f.cmd("diff", big, second))
            // Someone else moves origin: the lease on the last fetched state refuses to overwrite it.
            f.cmd("--git-dir=$remote", "update-ref", "refs/heads/pr/split", big)
            files.change(f.backend.state(ref).revision, listOf(VirtualFileChange("c.txt", "c")))
            assertFailsWith<IllegalStateException> {
                f.backend.commitToBranch(ref, "pr/split", "feat: c", isPush = true, isForce = true)
            }
            assertEquals(big, f.cmd("--git-dir=$remote", "rev-parse", "pr/split"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `a branch checked out in another worktree is published to origin only`() = runTest {
        val f = VirtualFixture()
        val other = Files.createTempDirectory("heartbeat-virtual-other-")
        val remote = Files.createTempDirectory("heartbeat-virtual-remote-")
        try {
            f.cmd("init", "--bare", remote.toString())
            f.cmd("remote", "add", "origin", remote.toString())
            f.cmd("branch", "pr/busy")
            f.cmd("push", "origin", "pr/busy")
            val tip = f.cmd("rev-parse", "pr/busy")
            Files.delete(other)
            f.cmd("worktree", "add", other.toString(), "pr/busy")
            val ref = f.create("busy")
            val files = assertNotNull(f.backend.find(ref))
            assertFailsWith<IllegalStateException> { f.backend.commitToBranch(ref, "pr/busy", "m", isPush = true) }
            f.backend.checkoutRef(ref, "pr/busy", isFetch = true)
            files.change(1, listOf(VirtualFileChange("busy.txt", "busy")))
            assertFailsWith<IllegalStateException> { f.backend.commitToBranch(ref, "pr/busy", "m") }
            val pushed = f.backend.commitToBranch(ref, "pr/busy", "feat: busy", isPush = true)
            assertEquals(pushed, f.cmd("--git-dir=$remote", "rev-parse", "pr/busy"))
            assertEquals(tip, f.cmd("rev-parse", "$pushed^"))
            assertEquals(tip, f.cmd("rev-parse", "refs/heads/pr/busy"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `a child continues its parent's branch and a fetch prefers origin over a stale local branch`() = runTest {
        val f = VirtualFixture()
        val other = Files.createTempDirectory("heartbeat-virtual-other-")
        val remote = Files.createTempDirectory("heartbeat-virtual-remote-")
        val writer = Files.createTempDirectory("heartbeat-virtual-writer-")
        try {
            f.cmd("init", "--bare", remote.toString())
            f.cmd("remote", "add", "origin", remote.toString())
            f.cmd("branch", "pr/shared")
            f.cmd("push", "origin", "pr/shared")
            Files.delete(other)
            f.cmd("worktree", "add", other.toString(), "pr/shared")
            // Someone else advances origin; the local branch of the other worktree stays behind.
            Files.delete(writer)
            f.cmd("clone", "--branch", "pr/shared", remote.toString(), writer.toString())
            Files.writeString(writer.resolve("remote.txt"), "remote")
            f.cmd("-C", writer.toString(), "add", "remote.txt")
            f.cmd("-C", writer.toString(), "-c", "user.name=W", "-c", "user.email=w@test.invalid", "commit", "-m", "r")
            f.cmd("-C", writer.toString(), "push", "origin", "pr/shared")
            val parent = f.create("parent")
            f.backend.checkoutRef(parent, "pr/shared", isFetch = true)
            assertEquals("remote", assertNotNull(f.backend.find(parent)).read("remote.txt"))
            val child = f.create("child", parent)
            val files = assertNotNull(f.backend.find(child))
            files.change(f.backend.state(child).revision, listOf(VirtualFileChange("child.txt", "child")))
            val pushed = f.backend.commitToBranch(child, "pr/shared", "feat: child", isPush = true)
            assertEquals(pushed, f.cmd("--git-dir=$remote", "rev-parse", "pr/shared"))
            // The parent's push is rejected until it catches up with the branch.
            val parentFiles = assertNotNull(f.backend.find(parent))
            parentFiles.change(f.backend.state(parent).revision, listOf(VirtualFileChange("parent.txt", "parent")))
            assertFailsWith<IllegalStateException> {
                f.backend.commitToBranch(parent, "pr/shared", "feat: parent", isPush = true)
            }
            f.backend.mergeRef(parent, "pr/shared", isFetch = true)
            val caught = f.backend.commitToBranch(parent, "pr/shared", "feat: parent", isPush = true)
            assertEquals(pushed, f.cmd("rev-parse", "$caught^"))
            assertEquals("child", f.cmd("show", "$caught:child.txt"))
            assertEquals("parent", f.cmd("show", "$caught:parent.txt"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `a loaded branch is never paused and follows a moved source without merging its files`() = runTest {
        val f = VirtualFixture()
        try {
            f.cmd("switch", "-c", "pr/loaded")
            Files.writeString(f.project.resolve("source.txt"), "pr version")
            f.cmd("commit", "-am", "pr work")
            f.cmd("switch", "main")
            val ref = f.create("loaded")
            f.backend.checkoutRef(ref, "pr/loaded", isFetch = false)
            Files.writeString(f.project.resolve("source.txt"), "main version")
            f.cmd("commit", "-am", "main work")
            val head = f.cmd("rev-parse", "HEAD")
            assertEquals(VirtualSandboxPhase.Ready, f.backend.pause(ref).phase)
            val resumed = f.backend.inspect(ref)
            assertEquals(VirtualSandboxPhase.Ready, resumed.phase)
            assertEquals(head, resumed.baseCommit)
            assertEquals(emptyList(), resumed.conflicts)
            assertEquals("pr version", assertNotNull(f.backend.find(ref)).read("source.txt"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `commit to branch refuses checked out diverged and malformed branches`() = runTest {
        val f = VirtualFixture()
        try {
            val ref = f.create("refuse")
            assertNotNull(f.backend.find(ref)).change(0, listOf(VirtualFileChange("source.txt", "x")))
            assertFailsWith<IllegalStateException> { f.backend.commitToBranch(ref, "main", "m") }
            assertFailsWith<IllegalStateException> { f.backend.commitToBranch(ref, "bad..name", "m") }
            assertFailsWith<IllegalArgumentException> { f.backend.commitToBranch(ref, "-d", "m") }
            assertFailsWith<IllegalArgumentException> { f.backend.commitToBranch(ref, "ok", " ") }
            val other = f.cmd("commit-tree", "4b825dc642cb6eb9a060e54bf8d69288fbee4904", "-m", "other")
            f.cmd("branch", "diverged", other)
            assertFailsWith<IllegalStateException> { f.backend.commitToBranch(ref, "diverged", "m") }
            assertEquals(other, f.cmd("rev-parse", "diverged"))
        } finally {
            f.close()
        }
    }
}
