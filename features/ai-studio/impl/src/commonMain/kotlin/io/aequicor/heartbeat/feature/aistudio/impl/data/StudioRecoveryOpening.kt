package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Opens an existing native context without carrying harnesses, switching its model or creating a replacement. */
@Inject
internal class StudioRecoveryOpening(private val facade: EngineFacade, private val workspaces: LocalWorkspaces) {
    private val log = Log.tag("StudioRecoveryOpening")

    suspend fun open(record: StudioChatRecord, current: ActiveSession?): ActiveSession {
        log.i { "Observe the saved native context without changing its configuration" }
        record.requireLiveExecutor()
        val ref = checkNotNull(record.ref) { "Native recovery needs a saved session" }
        val target = checkNotNull(record.target) { "Native recovery needs its saved target" }
        check(ref.engine == target.engine) { "Recovery target belongs to another engine" }
        val workspace = record.validatedExecutionWorkspace(
            workspaces,
            isLocalWorkspaceSupported = record.resolvedExecutionWorkspace() == null ||
                facade.engines.state.value.any {
                    it.descriptor.id == target.engine && it.descriptor.isLocalWorkspaceSupported
                },
        )
        if (current != null) {
            requireRecoveryRoute(record, current, workspace)
            return current
        }
        val active = facade.sessions.get(ref).features.requireFeature(ResumesSessions)
            .resume(studioResumeRequest(target, workspace, record.logicalSession, record.executorBinding()))
        var isValid = false
        try {
            requireRecoveryRoute(record, active, workspace)
            isValid = true
            return active
        } finally {
            if (!isValid) withContext(NonCancellable) { closeInvalid(active) }
        }
    }

    private fun requireRecoveryRoute(record: StudioChatRecord, active: ActiveSession, workspace: WorkspaceRef?) {
        val target = checkNotNull(record.target) { "Native recovery needs its saved target" }
        check(active.ref == record.ref) { "Native recovery opened another session" }
        check(active.route.engine == target.engine && active.route.binding == target.binding) {
            "Native recovery opened another connection"
        }
        check(active.route.workspace == workspace) { "Native recovery opened another workspace" }
    }

    private suspend fun closeInvalid(active: ActiveSession) {
        try {
            active.close()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e(error) { "Could not close an invalid recovery handle" }
        }
    }
}

/** A configuration model may change independently; observation never applies that change to a running turn. */
internal fun StudioChatRecord.hasSameRecoveryRoute(other: StudioChatRecord): Boolean =
    id == other.id && ref == other.ref && target?.engine == other.target?.engine &&
        target?.binding == other.target?.binding && resolvedExecutionWorkspace() == other.resolvedExecutionWorkspace()
