package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSeal
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import io.aequicor.heartbeat.feature.worktreemode.api.continueVirtual
import io.aequicor.heartbeat.feature.worktreemode.api.hasActiveBuilds
import io.aequicor.heartbeat.feature.worktreemode.api.sealed
import io.aequicor.heartbeat.feature.worktreemode.api.virtualChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Owns source observation and the cross-profile stop barrier, separate from legacy checkout operations. */
internal class VirtualTaskLifecycle(
    private val git: WorktreeGit,
    private val builds: WorktreeBuildCoordinator,
    private val facade: Lazy<EngineFacade>?,
    private val sourceBarriers: VirtualSourceBarrier,
    private val all: suspend () -> List<WorktreeRecord>,
    private val find: suspend (String) -> WorktreeRecord?,
    private val change: suspend (String, (WorktreeRecord) -> WorktreeRecord) -> WorktreeRecord?,
    private val settle: suspend (WorktreeIntent.Public.RunSettled) -> WorktreeRecord?,
    private val finishTask: suspend (String) -> WorktreeRecord?,
) {
    private val log = Log.tag("VirtualTaskLifecycle")
    private val changed = MutableStateFlow(0L)
    private var projection: (suspend (WorktreeTask) -> Unit)? = null

    fun changed() {
        changed.value += 1
        log.v { "Virtual workspace registry changed" }
    }

    suspend fun observe(emit: suspend (WorktreeTask) -> Unit) {
        val emitted = mutableMapOf<String, Long>()
        projection = { task ->
            if (emitted[task.chatId] != task.revision) {
                emit(task)
                emitted[task.chatId] = task.revision
            }
        }
        try {
            while (currentCoroutineContext().isActive) {
                val epoch = changed.value
                val projects = all().filter { it.task.virtualSandbox != null }.map { it.commonDirectory }.distinct()
                val active = projects.mapNotNull { related(it).firstOrNull() }
                if (active.isEmpty()) {
                    changed.first { it != epoch }
                } else {
                    active.forEach { tick(it) }
                    delay(POLL_MILLIS)
                }
            }
        } finally {
            withContext(NonCancellable) { sourceBarriers.detach(this@VirtualTaskLifecycle) }
            projection = null
        }
    }

    private val failedProjects = mutableSetOf<String>()
    private suspend fun tick(record: WorktreeRecord) {
        try {
            refresh(record, explicit = false)
            related(record.commonDirectory).forEach { current ->
                finishTask(current.task.chatId)?.let { projection?.invoke(it.task) }
            }
            failedProjects.remove(record.commonDirectory)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (failedProjects.add(record.commonDirectory)) {
                log.w(IllegalStateException("SandboxMonitorFailed (${error::class.simpleName.orEmpty()})")) {
                    "Virtual source remains unavailable"
                }
            }
        }
    }

    suspend fun captureChild(record: WorktreeRecord, completed: Boolean): WorktreeRecord {
        val child = record.parentWorkspace != null && record.task.virtualSandbox != null
        return if (child && completed) {
            val operation = Uuid.random().toString()
            val commit = git.seal(record, operation)
            change(record.task.chatId) {
                val results = it.task.virtualResultSnapshots + (checkNotNull(record.task.run).request.value to commit)
                it.copy(task = it.task.sealed(WorktreeSeal(operation, commit)).copy(virtualResultSnapshots = results))
            } ?: record
        } else {
            record
        }
    }

    suspend fun finish(record: WorktreeRecord): WorktreeRecord {
        if (record.task.virtualSandbox == null || record.task.phase != WorktreePhase.AwaitingDecision ||
            record.task.hasActiveBuilds()
        ) {
            return record
        }
        val state = git.handoffVirtual(record) ?: return record
        return change(record.task.chatId) { it.copy(task = it.task.virtualChanged(state)) } ?: record
    }

    /** The durable barrier precedes cancellation; no new base is published until every owner confirms exit. */
    suspend fun refresh(
        previous: WorktreeRecord,
        explicit: Boolean,
        resolveConflicts: Boolean = false,
    ): WorktreeRecord? {
        val project = previous.commonDirectory
        sourceBarriers.refresh(
            project,
            this,
            object : VirtualSourceParticipant {
                override suspend fun inspect(): Boolean {
                    var paused = false
                    related(project).forEach { record ->
                        val state = git.inspectVirtual(record) ?: return@forEach
                        val current = change(record.task.chatId) { it.copy(task = it.task.virtualChanged(state)) }
                        current?.let {
                            projection?.invoke(it.task)
                            if (state.phase == VirtualSandboxPhase.Conflicted && !state.isResolving) stop(it)
                        }
                        if (state.phase in setOf(
                                VirtualSandboxPhase.Pausing,
                                VirtualSandboxPhase.Waiting,
                            )
                        ) {
                            paused = true
                        }
                    }
                    return paused
                }

                override suspend fun pause(all: Boolean) {
                    related(project).forEach { record ->
                        if (!all &&
                            (
                                record.task.virtualSandbox?.isReady == true ||
                                    record.task.virtualSandbox?.isResolving == true
                            )
                        ) {
                            return@forEach
                        }
                        val state = git.pauseVirtual(record) ?: return@forEach
                        change(record.task.chatId) { it.copy(task = it.task.virtualChanged(state)) }
                            ?.let { projection?.invoke(it.task) }
                    }
                }

                override suspend fun stop(): Boolean {
                    var stopped = true
                    related(project).forEach { record ->
                        if (!stop(record)) stopped = false
                        if (!git.virtualSourceClean(record)) stopped = false
                    }
                    return stopped
                }

                override suspend fun resume(explicit: Boolean, resolve: Boolean) {
                    related(project).forEach { record ->
                        if (record.task.virtualSandbox?.phase != VirtualSandboxPhase.HandedOff) {
                            resume(
                                record,
                                explicit,
                                resolve,
                            )
                        }
                    }
                }
            },
            explicit,
            resolveConflicts,
        )
        return find(previous.task.chatId)
    }

    suspend fun related(project: String): List<WorktreeRecord> = all().filter {
        it.commonDirectory == project && it.task.virtualSandbox != null && it.task.executionWorkspace != null &&
            it.task.virtualSandbox?.phase != VirtualSandboxPhase.HandedOff
    }

    private suspend fun stop(previous: WorktreeRecord): Boolean {
        var current = find(previous.task.chatId) ?: return true
        if (current.task.virtualSandbox?.let {
                it.isReady || it.isResolving || it.phase == VirtualSandboxPhase.HandedOff
            } ==
            true
        ) {
            return true
        }
        var stopped = true
        current.task.builds.values.filter {
            it.phase !in setOf(
                WorktreeBuildPhase.Completed,
                WorktreeBuildPhase.Cancelled,
                WorktreeBuildPhase.Failed,
            )
        }.forEach {
            if (!builds.cancel(it.id)) stopped = false
        }
        val run = current.task.run
        if (run != null && run.outcome == null) {
            val session = run.session
            val target = current.runTarget
            if (session == null || target == null) {
                stopped = false
            } else {
                val capability = facade?.value?.engines?.features(session.engine)?.resolve(StopsOwnedTurns)
                val result = (capability as? FeatureAccess.Available)?.feature?.stop(
                    session,
                    run.request,
                    OwnedTurnAccess(target, current.task.executionWorkspace, run.turn),
                )
                if (result is OwnedTurnStop.Confirmed) {
                    current = settle(
                        WorktreeIntent.Public.RunSettled(
                            current.task.chatId,
                            run.request,
                            session,
                            result.turn.id,
                            checkNotNull(result.turn.outcome),
                        ),
                    ) ?: current
                } else {
                    stopped = false
                }
            }
        }
        return stopped
    }

    private suspend fun resume(current: WorktreeRecord, explicit: Boolean, resolveConflicts: Boolean) {
        val resumed = git.resumeVirtual(current, explicit, resolveConflicts) ?: return
        change(current.task.chatId) {
            var task = it.task.virtualChanged(resumed)
            if ((resumed.isReady || resumed.isResolving) && !task.chatId.startsWith("organism.")) {
                val prompt = if (resumed.isResolving) {
                    "The user asks you to resolve virtual rebase conflicts and continue the original task. " +
                        "Read each conflicting virtual file, then use sandbox_resolve_conflict for every resolution. " +
                        "Conflicts: ${resumed.conflicts.joinToString(", ")}. "
                } else {
                    "The source project is clean again and your virtual changes have been rebased " +
                        "to ${resumed.baseCommit}. " +
                        "Continue the original unfinished task from its current durable state; inspect " +
                        "changed files and rerun necessary project checks."
                }
                task = task.continueVirtual(Uuid.random().toString(), prompt)
            }
            it.copy(task = task)
        }?.let { projection?.invoke(it.task) }
    }

    private companion object {
        const val POLL_MILLIS = 2_000L
    }
}
