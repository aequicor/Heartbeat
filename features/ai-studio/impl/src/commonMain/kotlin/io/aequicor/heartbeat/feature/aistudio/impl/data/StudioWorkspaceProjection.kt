package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEnvironment
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioProject
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Shares sidebar projection and cached resume probes while retaining each chat's logical project identity. */
@Inject
internal class StudioWorkspaceProjection(
    private val facade: EngineFacade,
    private val workspaces: LocalWorkspaces,
    private val worktrees: StudioWorktrees,
) {
    private val log = Log.tag("StudioWorkspaceProjection")
    private val lock = Mutex()
    private val byRef = mutableMapOf<SessionRef, Boolean>()
    private var engines: Set<EngineId> = emptySet()

    fun observe(
        records: Flow<List<StudioChatRecord>?>,
        scope: CoroutineScope,
        running: Flow<Set<String>>,
        handle: suspend (String) -> ActiveSession?,
    ): Flow<StudioWorkspace> {
        val continuability = combine(
            records.map { rows -> rows.orEmpty().map { it.id to it.ref } }.distinctUntilChanged(),
            facade.engines.state,
            running.distinctUntilChanged(),
        ) { refs, _, _ ->
            log.d { "Recompute conversation continuability count=${refs.size}" }
            refs.associate { (id, ref) -> id to isContinuable(id, ref, handle) }
        }
        return combine(
            records,
            continuability,
            workspaces.observe(),
            worktrees.tasks(),
        ) { rows, available, projects, tasks ->
            StudioWorkspace(
                projects.map { StudioProject(it.ref.value, it.name, StudioEnvironment.Local, "") },
                rows.orEmpty().map { record ->
                    StudioSession(
                        record.id, record.projectId, record.title, record.updatedAt,
                        record.isPinned, record.isUnread, record.isArchived,
                        branch = tasks[record.id]?.branch,
                        modelId = record.target?.let { Json.encodeToString(EngineTarget.serializer(), it) },
                        isContinuable = available[record.id] ?: true,
                        isWorktree = record.worktreeTaskId != null,
                        nativeSession = record.ref,
                    )
                },
            )
        }.shareIn(scope, SharingStarted.WhileSubscribed(REUSE_TIMEOUT_MILLIS), replay = 1)
    }

    private suspend fun isContinuable(
        id: String,
        ref: SessionRef?,
        handle: suspend (String) -> ActiveSession?,
    ): Boolean {
        if (ref == null) return true
        val current = handle(id)
        if (current != null) {
            return current.state.value !is ActiveSessionState.Closing &&
                current.state.value != ActiveSessionState.Closed
        }
        val available = facade.engines.state.value.mapTo(mutableSetOf()) { it.descriptor.id }
        lock.withLock {
            if (engines != available) {
                engines = available
                byRef.clear()
            }
            byRef[ref]?.let { return it }
        }
        return try {
            val isResumable = facade.sessions.get(ref).features.resolve(ResumesSessions) is FeatureAccess.Available
            lock.withLock { byRef[ref] = isResumable }
            isResumable
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Stored chat cannot currently be resumed" }
            false
        }
    }

    private companion object {
        const val REUSE_TIMEOUT_MILLIS = 5_000L
    }
}
