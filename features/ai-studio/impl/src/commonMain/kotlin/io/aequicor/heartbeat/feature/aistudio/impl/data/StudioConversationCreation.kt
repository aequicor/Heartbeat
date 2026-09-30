package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Persists recoverable chat identity before profile-owned provisioning; closing a screen only detaches its waiter. */
@Inject
internal class StudioConversationCreation(
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val worktrees: StudioWorktrees,
) {
    private val log = Log.tag("StudioConversationCreation")

    suspend fun create(record: StudioChatRecord, save: suspend (StudioChatRecord) -> Unit): StudioChatRecord =
        profile.coroutineScope.async { provision(record, save, isNew = true) }.await().getOrThrow()

    /** Retries only startup identities whose preparation never reached the loaded durable journal. */
    fun recoverPending(records: suspend () -> List<StudioChatRecord>, save: suspend (StudioChatRecord) -> Unit) {
        profile.coroutineScope.launch {
            try {
                val pending = records().filter { it.worktreeTaskId != null && it.executionWorkspace == null }
                if (pending.isEmpty()) return@launch
                val loaded = worktrees.readyTasks().first()
                pending.filter { it.worktreeTaskId !in loaded }.forEach { record ->
                    launch {
                        log.i { "Resume conversation preparation absent from the loaded journal" }
                        provision(record, save, isNew = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.e(error) { "Could not inspect pending conversation preparation" }
            }
        }
    }

    private suspend fun provision(
        record: StudioChatRecord,
        save: suspend (StudioChatRecord) -> Unit,
        isNew: Boolean,
    ): Result<StudioChatRecord> = try {
        if (isNew) {
            log.i { "Persist conversation before provisioning" }
            save(record)
        }
        if (record.worktreeTaskId == null) {
            Result.success(record)
        } else {
            val project = WorkspaceRef(checkNotNull(record.projectId))
            worktrees.send(WorktreeIntent.Public.Prepare(record.worktreeTaskId, project))
            val task = worktrees.await(record.worktreeTaskId) {
                it.executionWorkspace != null || it.phase == WorktreePhase.Failed
            }
            check(task.executionWorkspace != null && task.phase != WorktreePhase.Failed) {
                "Worktree preparation failed: ${task.failure.orEmpty()}"
            }
            Result.success(
                record.copy(executionWorkspace = task.executionWorkspace, hasFailed = false).also { save(it) },
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.e(error) { "Conversation preparation failed; retain its recoverable identity" }
        markFailed(record, save)
        Result.failure(error)
    }

    /** Restores a final checkout write interrupted by profile shutdown. */
    fun recovered(records: List<StudioChatRecord>, tasks: Map<String, WorktreeTask>): List<StudioChatRecord> =
        records.map { record ->
            val workspace = record.worktreeTaskId?.let(tasks::get)?.executionWorkspace
            if (record.executionWorkspace == null && workspace != null) {
                record.copy(executionWorkspace = workspace)
            } else {
                record
            }
        }

    /** Keeps edits made while provisioning was running. */
    fun updated(records: List<StudioChatRecord>, changed: StudioChatRecord): List<StudioChatRecord> =
        if (records.none { it.id == changed.id }) {
            records + changed
        } else {
            records.map {
                if (it.id == changed.id) {
                    it.copy(executionWorkspace = changed.executionWorkspace, hasFailed = changed.hasFailed)
                } else {
                    it
                }
            }
        }

    private suspend fun markFailed(record: StudioChatRecord, save: suspend (StudioChatRecord) -> Unit) {
        try {
            save(record.copy(hasFailed = true))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e(error) { "Could not persist preparation failure" }
        }
    }
}
