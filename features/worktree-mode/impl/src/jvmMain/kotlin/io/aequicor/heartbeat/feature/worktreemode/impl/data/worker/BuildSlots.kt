package io.aequicor.heartbeat.feature.worktreemode.impl.data.worker

import io.aequicor.heartbeat.feature.worktreemode.impl.data.SandboxBuild
import io.aequicor.heartbeat.feature.worktreemode.impl.data.slotDirectory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

private val slotLog = io.aequicor.heartbeat.core.logging.Log.tag("WorktreeBuildSlots")
private const val GIT_TIMEOUT_MILLIS = 300_000L
private const val GIT_POLL_MILLIS = 50L
private const val SHORT_SHA = 12

/** Slot preparation stopped by cancellation or parent loss; [reason] is the worker's stop code. */
internal class SlotPreparationStopped(val reason: String) : Exception(reason)

/**
 * Brings the exclusively locked build slot to the sandbox snapshot. Tracked files follow the snapshot exactly and
 * untracked files are removed, while ignored build outputs (`build/`, `.gradle`) stay for incremental builds.
 * A slot whose checkout is unusable is recreated inside its pool only. [stopped] is polled while Git runs, so a
 * cancellation or parent loss ends the preparation and its Git process tree.
 */
internal fun prepareSlot(sandbox: SandboxBuild, stopped: () -> String? = { null }): String {
    val pool = Path.of(sandbox.pool).toAbsolutePath().normalize()
    val data = checkNotNull(pool.parent) { "InvalidSlotPool" }
    check(pool.fileName.toString() == "slots" && data.fileName?.toString() == "heartbeat-worktree-data") {
        "InvalidSlotPool"
    }
    // A symbolic link could redirect slot removal outside the repository's private data directory.
    check(!Files.isSymbolicLink(pool) && !Files.isSymbolicLink(data)) { "InvalidSlotPool" }
    val index = checkNotNull(sandbox.slot) { "SlotUnassigned" }
    val slot = slotDirectory(pool.toString(), index).normalize()
    check(slot.parent == pool) { "InvalidSlotDirectory" }
    if (isSlotCheckout(slot, stopped)) {
        slotGit(slot, stopped, "checkout", "--detach", "--force", sandbox.snapshot)
        slotGit(slot, stopped, "clean", "-d", "--force", "--quiet")
        return "[sandbox] build slot $index synced to ${sandbox.snapshot.take(SHORT_SHA)}\n"
    }
    if (Files.exists(slot, LinkOption.NOFOLLOW_LINKS)) removeSlot(slot)
    Files.createDirectories(pool)
    // --force reuses a registration whose directory vanished; other worktrees of the repository stay untouched.
    slotGit(
        Path.of(sandbox.repository),
        stopped,
        "worktree",
        "add",
        "--detach",
        "--force",
        slot.toString(),
        sandbox.snapshot,
    )
    return "[sandbox] build slot $index created at ${sandbox.snapshot.take(SHORT_SHA)}\n"
}

/** The snapshot stays reachable from the slot's HEAD; the private ref is only needed until the build ends. */
internal fun releaseSnapshot(sandbox: SandboxBuild, operation: String) {
    try {
        slotGit(Path.of(sandbox.repository), { null }, "update-ref", "-d", "refs/heartbeat/snapshots/$operation")
    } catch (error: IllegalStateException) {
        slotLog.w(error) { "Sandbox snapshot ref remains until the next journal restoration" }
    } catch (error: java.io.IOException) {
        slotLog.w(IllegalStateException("SnapshotReleaseFailed (${error::class.simpleName.orEmpty()})")) {
            "Sandbox snapshot ref remains until the next journal restoration"
        }
    }
}

private fun isSlotCheckout(slot: Path, stopped: () -> String?): Boolean =
    Files.exists(slot.resolve(".git"), LinkOption.NOFOLLOW_LINKS) && try {
        slotGit(slot, stopped, "rev-parse", "--verify", "HEAD^{commit}")
        true
    } catch (error: IllegalStateException) {
        slotLog.w(error) { "Build slot checkout is unusable and will be recreated" }
        false
    }

/** Walking does not follow links, so a link inside the slot is removed itself, never its target. */
internal fun removeSlot(slot: Path) {
    Files.walk(slot).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

/** Git output may contain paths and is neither logged nor returned. */
private fun slotGit(directory: Path, stopped: () -> String?, vararg arguments: String) {
    val output = Files.createTempFile("heartbeat-slot-git-", ".out")
    var process: Process? = null
    try {
        process = ProcessBuilder(listOf("git", "--no-optional-locks") + arguments)
            .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GIT_TIMEOUT_MILLIS)
        while (!process.waitFor(GIT_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            stopped()?.let { throw SlotPreparationStopped(it) }
            check(System.nanoTime() < deadline) { "SlotGitTimedOut" }
        }
        check(process.exitValue() == 0) { "SlotGitFailed" }
    } finally {
        process?.let(::stopGitTree)
        Files.deleteIfExists(output)
    }
}

private fun stopGitTree(process: Process) {
    val tree = process.descendants().use { stream -> stream.toList() }
    tree.filter { it.isAlive }.forEach { it.destroyForcibly() }
    if (process.isAlive) process.destroyForcibly()
    while (process.isAlive || tree.any { it.isAlive }) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(GIT_POLL_MILLIS))
    }
}
