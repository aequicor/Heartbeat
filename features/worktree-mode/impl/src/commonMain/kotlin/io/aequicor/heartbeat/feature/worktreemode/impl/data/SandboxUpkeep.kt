package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.hasActiveBuilds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

private val upkeepLog = Log.tag("SandboxUpkeep")
private val terminalPhases = setOf(
    WorktreeBuildPhase.Completed,
    WorktreeBuildPhase.Cancelled,
    WorktreeBuildPhase.Failed,
)

/**
 * A retained sandbox keeps only sources: ignored outputs left by native agent builds are removed once, and the
 * marker resets when the task resumes work. The cleanup holds the task's [lock], so a concurrent completion choice
 * cannot resume work under it; a busy task is skipped until the next check.
 */
internal suspend fun reclaimRetainedSandbox(
    git: WorktreeGit,
    lock: Mutex,
    current: suspend () -> WorktreeRecord?,
    mark: suspend (isReclaimed: Boolean) -> Unit,
) {
    val record = current() ?: return
    if (!record.task.isSandbox || record.provisioning.isBlank()) return
    if (!record.isIdleSandbox()) {
        if (record.isOutputsReclaimed) mark(false)
        return
    }
    if (record.isOutputsReclaimed || !lock.tryLock()) return
    try {
        val locked = current()?.takeIf { it.isIdleSandbox() && !it.isOutputsReclaimed }
        if (locked != null && reclaimSandboxOutputs(git, locked)) mark(true)
    } finally {
        lock.unlock()
    }
}

private fun WorktreeRecord.isIdleSandbox(): Boolean = task.phase == WorktreePhase.Retained && !task.hasActiveBuilds()

/** Removes ignored outputs of one retained sandbox; a failure keeps them for the next retained check. */
private suspend fun reclaimSandboxOutputs(git: WorktreeGit, record: WorktreeRecord): Boolean = try {
    git.reclaim(record)
    upkeepLog.i { "Sandbox outputs reclaimed" }
    true
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    upkeepLog.w(IllegalStateException("SandboxReclaimFailed (${error::class.simpleName.orEmpty()})")) {
        "Sandbox outputs remain until the next retained check"
    }
    false
}

/** Snapshot refs left by crashes or dropped builds; one pass per repository with sandbox tasks after restoration. */
internal suspend fun pruneSandboxSnapshots(git: WorktreeGit, records: List<WorktreeRecord>) {
    val active = records.flatMap { record ->
        record.jobs.keys.filter { record.task.builds[it]?.phase !in terminalPhases }
    }.toSet()
    records.filter { it.task.isSandbox && it.provisioning.isNotBlank() }.distinctBy { it.commonDirectory }
        .forEach { record ->
            try {
                git.pruneSnapshots(record, active)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                upkeepLog.w(IllegalStateException("SnapshotPruneFailed (${error::class.simpleName.orEmpty()})")) {
                    "Stale sandbox snapshots remain until the next restoration"
                }
            }
        }
}
