package io.aequicor.heartbeat.feature.worktreemode.impl.data.worker

import io.aequicor.heartbeat.feature.worktreemode.impl.data.SandboxBuild
import io.aequicor.heartbeat.feature.worktreemode.impl.data.slotDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val slotLog = io.aequicor.heartbeat.core.logging.Log.tag("WorktreeBuildSlots")
private const val GIT_TIMEOUT_SECONDS = 300L

/**
 * Brings the exclusively locked build slot to the sandbox snapshot. Tracked files follow the snapshot exactly and
 * untracked files are removed, while ignored build outputs (`build/`, `.gradle`) stay for incremental builds.
 * A slot whose checkout is unusable is recreated inside its pool only.
 */
internal fun prepareSlot(sandbox: SandboxBuild): String {
    val pool = Path.of(sandbox.pool).toAbsolutePath().normalize()
    check(pool.fileName.toString() == "slots" && pool.parent?.fileName?.toString() == "heartbeat-worktree-data") {
        "InvalidSlotPool"
    }
    val index = checkNotNull(sandbox.slot) { "SlotUnassigned" }
    val slot = slotDirectory(pool.toString(), index).normalize()
    check(slot.parent == pool) { "InvalidSlotDirectory" }
    if (isSlotCheckout(slot)) {
        slotGit(slot, "checkout", "--detach", "--force", sandbox.snapshot)
        slotGit(slot, "clean", "-d", "--force", "--quiet")
        return "[sandbox] build slot $index synced to ${sandbox.snapshot.take(SHORT_SHA)}\n"
    }
    if (Files.exists(slot)) removeSlot(slot)
    Files.createDirectories(pool)
    slotGit(Path.of(sandbox.repository), "worktree", "prune")
    slotGit(Path.of(sandbox.repository), "worktree", "add", "--detach", "--force", slot.toString(), sandbox.snapshot)
    return "[sandbox] build slot $index created at ${sandbox.snapshot.take(SHORT_SHA)}\n"
}

/** The snapshot stays reachable from the slot's HEAD; the private ref is only needed until the build ends. */
internal fun releaseSnapshot(sandbox: SandboxBuild, operation: String) {
    try {
        slotGit(Path.of(sandbox.repository), "update-ref", "-d", "refs/heartbeat/snapshots/$operation")
    } catch (error: IllegalStateException) {
        slotLog.w(error) { "Sandbox snapshot ref remains until the next cleanup" }
    }
}

private const val SHORT_SHA = 12

private fun isSlotCheckout(slot: Path): Boolean = Files.exists(slot.resolve(".git")) && try {
    slotGit(slot, "rev-parse", "--verify", "HEAD^{commit}")
    true
} catch (error: IllegalStateException) {
    slotLog.w(error) { "Build slot checkout is unusable and will be recreated" }
    false
}

private fun removeSlot(slot: Path) {
    Files.walk(slot).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

/** Git output may contain paths and is neither logged nor returned. */
private fun slotGit(directory: Path, vararg arguments: String) {
    val output = Files.createTempFile("heartbeat-slot-git-", ".out")
    try {
        val process = ProcessBuilder(listOf("git", "--no-optional-locks") + arguments)
            .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("SlotGitTimedOut")
        }
        check(process.exitValue() == 0) { "SlotGitFailed" }
    } finally {
        Files.deleteIfExists(output)
    }
}
