package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualFileChange
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
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
        val remote = Files.createTempDirectory("heartbeat-virtual-remote-")
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
            f.cmd("init", "--bare", remote.toString())
            f.cmd("remote", "add", "origin", remote.toString())
            val pushed = f.backend.commitToBranch(ref, "pr/work", "feat: push", isPush = true)
            assertEquals(pushed, f.cmd("--git-dir=$remote", "rev-parse", "pr/work"))
        } finally {
            removeTempDirectories(remote)
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
            assertTrue("pr work" in f.backend.branchHistory(ref, "pr/merge", "main", isStat = true))
            val merged = f.backend.mergeRef(ref, "main", isFetch = false)
            assertTrue(merged.conflicts.isEmpty())
            assertEquals("from main", files.read("main.txt"))
            assertEquals("from pr", files.read("pr.txt"))
            assertEquals(merged, f.backend.mergeRef(ref, "main", isFetch = false))
            val commit = f.backend.commitToBranch(ref, "pr/merge", "Merge main")
            assertEquals("$tip $main", f.cmd("show", "-s", "--format=%P", commit))
            assertEquals(
                "Sandbox Test sandbox@test.invalid Sandbox Test sandbox@test.invalid",
                f.cmd("show", "-s", "--format=%an %ae %cn %ce", commit),
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
            removeTempDirectories(remote)
            f.close()
        }
    }

    @Test
    fun `an earlier commit only origin has is loaded although the local branch lags behind`() = runTest {
        val f = VirtualFixture()
        val remote = Files.createTempDirectory("heartbeat-virtual-remote-")
        val writer = Files.createTempDirectory("heartbeat-virtual-writer-")
        try {
            f.cmd("init", "--bare", remote.toString())
            f.cmd("remote", "add", "origin", remote.toString())
            f.cmd("branch", "pr/lag")
            f.cmd("push", "origin", "pr/lag")
            Files.delete(writer)
            f.cmd("clone", "--branch", "pr/lag", remote.toString(), writer.toString())
            val identity = arrayOf("-c", "user.name=W", "-c", "user.email=w@test.invalid")
            Files.writeString(writer.resolve("one.txt"), "one")
            f.cmd("-C", writer.toString(), "add", "one.txt")
            f.cmd("-C", writer.toString(), *identity, "commit", "-m", "one")
            val one = f.cmd("-C", writer.toString(), "rev-parse", "HEAD")
            Files.writeString(writer.resolve("two.txt"), "two")
            f.cmd("-C", writer.toString(), "add", "two.txt")
            f.cmd("-C", writer.toString(), *identity, "commit", "-m", "two")
            f.cmd("-C", writer.toString(), "push", "origin", "pr/lag")
            f.cmd("fetch", "origin")
            val ref = f.create("lag")
            f.backend.checkoutRef(ref, "pr/lag", isFetch = false, at = one)
            val files = assertNotNull(f.backend.find(ref))
            assertEquals("one", files.read("one.txt"))
            assertEquals(null, files.read("two.txt"))
            val elsewhere = f.cmd("commit-tree", "4b825dc642cb6eb9a060e54bf8d69288fbee4904", "-m", "elsewhere")
            assertFailsWith<IllegalStateException> {
                f.backend.checkoutRef(ref, "pr/lag", isFetch = false, at = elsewhere)
            }
        } finally {
            removeTempDirectories(remote, writer)
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
            removeTempDirectories(remote, other)
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
            removeTempDirectories(remote, other, writer)
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

    @Test
    fun `a conflicted merge of a loaded branch survives inspect pause and resume and commits as a merge`() = runTest {
        val f = VirtualFixture()
        try {
            f.cmd("switch", "-c", "pr/conflict")
            Files.writeString(f.project.resolve("source.txt"), "pr\n")
            f.cmd("commit", "-am", "pr work")
            val tip = f.cmd("rev-parse", "HEAD")
            f.cmd("switch", "main")
            Files.writeString(f.project.resolve("source.txt"), "main\n")
            f.cmd("commit", "-am", "main work")
            val main = f.cmd("rev-parse", "HEAD")
            val ref = f.create("mergeconflict")
            val files = assertNotNull(f.backend.find(ref))
            f.backend.checkoutRef(ref, "pr/conflict", isFetch = false)
            val conflicted = f.backend.mergeRef(ref, "main", isFetch = false)
            assertEquals(VirtualSandboxPhase.Conflicted, conflicted.phase)
            assertEquals(listOf("source.txt"), conflicted.conflicts)
            assertRefused("SandboxConflicted") { f.backend.commitToBranch(ref, "pr/conflict", "m") }
            assertEquals(conflicted, f.backend.inspect(ref))
            assertEquals(conflicted, f.backend.pause(ref))
            // The project checkout moves: the loaded branch re-anchors and keeps its conflict.
            Files.writeString(f.project.resolve("other.txt"), "other")
            f.cmd("add", "other.txt")
            f.cmd("commit", "-m", "source moves")
            val head = f.cmd("rev-parse", "HEAD")
            val moved = f.backend.inspect(ref)
            assertEquals(VirtualSandboxPhase.Conflicted, moved.phase)
            assertEquals(head, moved.baseCommit)
            assertEquals(listOf("source.txt"), moved.conflicts)
            assertEquals(moved, f.backend.resume(ref, explicit = true))
            val resolving = f.backend.resume(ref, explicit = true, resolveConflicts = true)
            assertTrue(resolving.isResolving)
            assertTrue(assertNotNull(files.read("source.txt")).contains("<<<<<<<"))
            assertRefused("SandboxConflicted") { f.backend.commitToBranch(ref, "pr/conflict", "m") }
            assertRefused("SandboxConflicted") { f.backend.checkoutRef(ref, "main", isFetch = false) }
            assertRefused("SandboxConflicted") { f.backend.mergeRef(ref, "main", isFetch = false) }
            assertEquals(VirtualSandboxPhase.Ready, f.backend.resolve(ref, "source.txt", "resolved\n").phase)
            val commit = f.backend.commitToBranch(ref, "pr/conflict", "Merge main")
            assertEquals("$tip $main", f.cmd("show", "-s", "--format=%P", commit))
            assertEquals("resolved", f.cmd("show", "$commit:source.txt"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `a conflicted child integration of a loaded branch survives inspect and resume`() = runTest {
        val f = VirtualFixture()
        try {
            f.cmd("branch", "pr/integrate")
            val parent = f.create("intparent")
            f.backend.checkoutRef(parent, "pr/integrate", isFetch = false)
            val child = f.create("intchild", parent)
            val childFiles = assertNotNull(f.backend.find(child))
            childFiles.change(f.backend.state(child).revision, listOf(VirtualFileChange("source.txt", "child\n")))
            val parentFiles = assertNotNull(f.backend.find(parent))
            parentFiles.change(f.backend.state(parent).revision, listOf(VirtualFileChange("source.txt", "parent\n")))
            val conflicted = f.backend.integrate(parent, child)
            assertEquals(VirtualSandboxPhase.Conflicted, conflicted.phase)
            assertEquals(conflicted, f.backend.inspect(parent))
            assertTrue(f.backend.resume(parent, explicit = true, resolveConflicts = true).isResolving)
            assertEquals(VirtualSandboxPhase.Ready, f.backend.resolve(parent, "source.txt", "both\n").phase)
            assertEquals("both\n", parentFiles.read("source.txt"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `a failed push of a branch checked out elsewhere is retried without a duplicate commit`() = runTest {
        val f = VirtualFixture()
        val other = Files.createTempDirectory("heartbeat-virtual-other-")
        val remote = Files.createTempDirectory("heartbeat-virtual-remote-")
        try {
            f.cmd("init", "--bare", remote.toString())
            f.cmd("remote", "add", "origin", remote.toString())
            f.cmd("branch", "pr/retry")
            f.cmd("push", "origin", "pr/retry")
            val tip = f.cmd("rev-parse", "pr/retry")
            Files.delete(other)
            f.cmd("worktree", "add", other.toString(), "pr/retry")
            val ref = f.create("retry")
            f.backend.checkoutRef(ref, "pr/retry", isFetch = true)
            val files = assertNotNull(f.backend.find(ref))
            files.change(f.backend.state(ref).revision, listOf(VirtualFileChange("retry.txt", "retry")))
            f.cmd("remote", "set-url", "origin", remote.resolveSibling("${remote.fileName}-missing").toString())
            assertRefused(
                "SandboxGitFailed",
            ) { f.backend.commitToBranch(ref, "pr/retry", "feat: retry", isPush = true) }
            f.cmd("remote", "set-url", "origin", remote.toString())
            val pushed = f.backend.commitToBranch(ref, "pr/retry", "feat: retry", isPush = true)
            assertEquals(pushed, f.cmd("--git-dir=$remote", "rev-parse", "pr/retry"))
            assertEquals(tip, f.cmd("rev-parse", "$pushed^"))
            assertEquals(pushed, f.cmd("rev-parse", "refs/heartbeat/virtual/retry/$pushed"))
            assertEquals(tip, f.cmd("rev-parse", "refs/heads/pr/retry"))
        } finally {
            removeTempDirectories(remote, other)
            f.close()
        }
    }

    @Test
    fun `an own branch catch-up after a rejected push commits on top of the unpushed local commit`() = runTest {
        val f = VirtualFixture()
        val remote = Files.createTempDirectory("heartbeat-virtual-remote-")
        val writer = Files.createTempDirectory("heartbeat-virtual-writer-")
        try {
            f.cmd("init", "--bare", remote.toString())
            f.cmd("remote", "add", "origin", remote.toString())
            f.cmd("branch", "pr/own")
            f.cmd("push", "origin", "pr/own")
            val ref = f.create("own")
            val files = assertNotNull(f.backend.find(ref))
            f.backend.checkoutRef(ref, "pr/own", isFetch = true)
            files.change(f.backend.state(ref).revision, listOf(VirtualFileChange("a.txt", "a")))
            f.backend.commitToBranch(ref, "pr/own", "feat: a", isPush = true)
            Files.delete(writer)
            f.cmd("clone", "--branch", "pr/own", remote.toString(), writer.toString())
            Files.writeString(writer.resolve("w.txt"), "w")
            f.cmd("-C", writer.toString(), "add", "w.txt")
            f.cmd("-C", writer.toString(), "-c", "user.name=W", "-c", "user.email=w@test.invalid", "commit", "-m", "w")
            f.cmd("-C", writer.toString(), "push", "origin", "pr/own")
            val theirs = f.cmd("-C", writer.toString(), "rev-parse", "HEAD")
            files.change(f.backend.state(ref).revision, listOf(VirtualFileChange("b.txt", "b")))
            assertRefused("SandboxGitFailed") { f.backend.commitToBranch(ref, "pr/own", "feat: b", isPush = true) }
            val unpushed = f.cmd("rev-parse", "pr/own")
            f.backend.mergeRef(ref, "pr/own", isFetch = true)
            val caught = f.backend.commitToBranch(ref, "pr/own", "feat: b", isPush = true)
            assertEquals(caught, f.cmd("--git-dir=$remote", "rev-parse", "pr/own"))
            assertEquals(caught, f.cmd("rev-parse", "pr/own"))
            assertEquals("$unpushed $theirs", f.cmd("show", "-s", "--format=%P", caught))
            assertEquals("w", f.cmd("show", "$caught:w.txt"))
            assertEquals("b", f.cmd("show", "$caught:b.txt"))
        } finally {
            removeTempDirectories(remote, writer)
            f.close()
        }
    }

    @Test
    fun `without a fetch the local branch wins over its origin state`() = runTest {
        val f = VirtualFixture()
        val remote = Files.createTempDirectory("heartbeat-virtual-remote-")
        try {
            f.cmd("init", "--bare", remote.toString())
            f.cmd("remote", "add", "origin", remote.toString())
            f.cmd("switch", "-c", "pr/ahead")
            f.cmd("push", "origin", "pr/ahead")
            Files.writeString(f.project.resolve("ahead.txt"), "local")
            f.cmd("add", "ahead.txt")
            f.cmd("commit", "-m", "local only")
            f.cmd("switch", "main")
            val ref = f.create("ahead")
            assertTrue("local only" in f.backend.branchHistory(ref, "pr/ahead", "main", isStat = false))
            f.backend.mergeRef(ref, "pr/ahead", isFetch = false)
            assertEquals("local", assertNotNull(f.backend.find(ref)).read("ahead.txt"))
        } finally {
            removeTempDirectories(remote)
            f.close()
        }
    }

    @Test
    fun `branch operations refuse a changed revision and force needs a loaded branch`() = runTest {
        val f = VirtualFixture()
        try {
            f.cmd("branch", "pr/other")
            val ref = f.create("revision")
            assertNotNull(f.backend.find(ref)).change(0, listOf(VirtualFileChange("source.txt", "x")))
            assertRefused("SandboxRevisionChanged") {
                f.backend.commitToBranch(ref, "pr/new", "m", expectedRevision = 0)
            }
            assertRefused("SandboxRevisionChanged") {
                f.backend.checkoutRef(ref, "pr/other", isFetch = false, expectedRevision = 0)
            }
            assertRefused("SandboxBranchNotLoaded") { f.backend.commitToBranch(ref, "pr/other", "m", isForce = true) }
            assertEquals(f.cmd("rev-parse", "main"), f.cmd("rev-parse", "pr/other"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `restore reads a hex-looking branch name as the branch and accepts a trailing slash`() = runTest {
        val f = VirtualFixture()
        try {
            f.cmd("switch", "-c", "abcdef1")
            Files.createDirectories(f.project.resolve("dir"))
            Files.writeString(f.project.resolve("dir/x.txt"), "x")
            f.cmd("add", "dir/x.txt")
            f.cmd("commit", "-m", "dir")
            f.cmd("switch", "main")
            val ref = f.create("restore")
            f.backend.restorePaths(ref, listOf("dir/"), "abcdef1")
            assertEquals("x", assertNotNull(f.backend.find(ref)).read("dir/x.txt"))
        } finally {
            f.close()
        }
    }
}

/** Temporary remotes, clones and worktrees outside the fixture root; a missing one was never recreated. */
private fun removeTempDirectories(vararg directories: Path) =
    directories.filter { Files.exists(it) }.forEach { removeTestTree(it.toRealPath()) }

private suspend fun assertRefused(code: String, block: suspend () -> Any) {
    assertEquals(code, assertFailsWith<RuntimeException> { block() }.message)
}
