package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.hasActiveBuilds
import io.aequicor.heartbeat.feature.worktreemode.api.transition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val upkeepLog = Log.tag("SandboxUpkeep")
private val terminalPhases = setOf(
    WorktreeBuildPhase.Completed,
    WorktreeBuildPhase.Cancelled,
    WorktreeBuildPhase.Failed,
)

/** Per-task cleanup barriers are independent of the journal's IO and of long completion-action preparation. */
internal class SandboxUpkeep(private val git: WorktreeGit) {
    private val mutex = Mutex()
    private val locks = mutableMapOf<String, Mutex>()

    suspend fun <T> prepareRun(chatId: String, block: suspend () -> T): T = lock(chatId).withLock { block() }

    suspend fun reclaim(
        chatId: String,
        preparation: Mutex,
        current: suspend () -> WorktreeRecord?,
        mark: suspend (Boolean) -> Unit,
    ) {
        if (!preparation.tryLock()) return
        try {
            reclaimRetainedSandbox(git, lock(chatId), current, mark)
        } finally {
            preparation.unlock()
        }
    }

    private suspend fun lock(chatId: String): Mutex = mutex.withLock { locks.getOrPut(chatId) { Mutex() } }
}

/** Clears cleanup bookkeeping atomically with an accepted turn, before it can resume native work. */
internal fun WorktreeRecord.startingRun(command: WorktreeIntent.Public.RunStarted): WorktreeRecord {
    val next = task.transition(command)
    return copy(task = next, isOutputsReclaimed = isOutputsReclaimed && next == task)
}

/**
 * A retained sandbox keeps only sources: ignored outputs left by native agent builds are removed once, and the
 * marker resets when the task resumes work. The cleanup shares the task's [lock] with new-turn preparation, so
 * native work cannot resume under it; a busy task is skipped until the next check.
 */
internal suspend fun reclaimRetainedSandbox(
    git: WorktreeGit,
    lock: Mutex,
    current: suspend () -> WorktreeRecord?,
    mark: suspend (isReclaimed: Boolean) -> Unit,
) {
    if (!lock.tryLock()) return
    try {
        val record = current() ?: return
        if (!record.task.isSandbox || record.provisioning.isBlank()) return
        if (!record.isIdleSandbox()) {
            if (record.isOutputsReclaimed) mark(false)
        } else if (!record.isOutputsReclaimed && reclaimSandboxOutputs(git, record)) {
            mark(true)
        }
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
