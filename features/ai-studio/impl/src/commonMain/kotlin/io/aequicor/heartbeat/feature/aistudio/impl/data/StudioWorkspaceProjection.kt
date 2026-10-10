package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEnvironment
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioProject
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.Json

/** Shares sidebar projection and cached resume probes while retaining each chat's logical project identity. */
@Inject
internal class StudioWorkspaceProjection(
    private val facade: EngineFacade,
    private val workspaces: LocalWorkspaces,
    private val worktrees: StudioWorktrees,
    private val checklists: StudioChecklists,
) {
    private val log = Log.tag("StudioWorkspaceProjection")

    fun observe(
        records: Flow<List<StudioChatRecord>?>,
        scope: CoroutineScope,
        running: Flow<Set<String>>,
        handle: suspend (String) -> ActiveSession?,
    ): Flow<StudioWorkspace> {
        val probes = StudioContinuability(facade, handle)
        val continuability = combine(
            records.map { rows -> rows.orEmpty().map { it.id to it.ref } }.distinctUntilChanged(),
            facade.engines.state,
            running.distinctUntilChanged(),
        ) { refs, _, _ ->
            log.d { "Recompute conversation continuability count=${refs.size}" }
            refs.associate { (id, ref) -> id to probes.isContinuable(id, ref) }
        }
        return combine(
            records,
            continuability,
            workspaces.observe(),
            worktrees.tasks(),
            checklists.events,
        ) { rows, available, projects, tasks, checklistEvents ->
            StudioWorkspace(
                projects.map { StudioProject(it.ref.value, it.name, StudioEnvironment.Local, "") },
                rows.orEmpty().map { record ->
                    val workspace = record.executionWorkspace ?: record.projectId?.let(::WorkspaceRef)
                    StudioSession(
                        record.id, record.projectId, record.title, record.updatedAt,
                        record.isPinned, record.isUnread, record.isArchived,
                        branch = tasks[record.id]?.branch,
                        modelId = record.target?.let { Json.encodeToString(EngineTarget.serializer(), it) },
                        // The organism drives the sessions of its chat; the studio only shows them.
                        isContinuable = record.organismId == null && (available[record.id] ?: true),
                        isWorktree = record.worktreeTaskId != null,
                        isOrganism = record.organismId != null,
                        nativeSession = record.ref,
                        parentChatId = record.helper?.parentChatId,
                        isAwaitingChecklist = record.checklistReadiness(checklistEvents).first,
                        isReady = record.checklistReadiness(checklistEvents).second,
                        treeAccess = record.target?.let {
                            io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess(
                                it,
                                workspace,
                            )
                        },
                    )
                },
            )
        }.shareIn(scope, SharingStarted.WhileSubscribed(REUSE_TIMEOUT_MILLIS), replay = 1)
    }

    private companion object {
        const val REUSE_TIMEOUT_MILLIS = 5_000L
    }
}
