package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.platform.dibundle.AppShutdownAccessors
import io.aequicor.heartbeat.platform.dibundle.HeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.awaitWorkerRecovery
import io.aequicor.heartbeat.platform.dibundle.closeScopes
import io.aequicor.heartbeat.platform.dibundle.createHeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.isBackgroundWorkerEnabled
import io.aequicor.heartbeat.platform.dibundle.workerRuntimeStatus
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Profile services already own replay, fencing and the shared build queue. The host only assembles their graph. */
internal class DesktopProcessWorkerSession(
    private val profile: String?,
    private val isDevelopment: Boolean,
    private val hasLocalSecrets: Boolean,
) : ProcessWorkerSession {
    private var graph: HeartbeatGraph? = null
    private var session: ProfileSession? = null

    override suspend fun open() {
        val app = createHeartbeatGraph(isDevelopment, hasLocalSecrets, allowSecretInteraction = false)
        graph = app
        if (!app.isBackgroundWorkerEnabled()) throw WorkerStartRefused(WorkerProblem.FeatureDisabled)
        val opened = if (profile == null) {
            app.profileSessions.restore()
        } else {
            app.profileSessions.open(
                ProfileId(profile),
            )
        }
        session = checkNotNull(opened) { "No saved profile; select a profile in Heartbeat first" }
    }

    override val status: Flow<WorkerSnapshot> get() = checkNotNull(session).workerRuntimeStatus().map { status ->
        val problem = when {
            status.hasFailed -> WorkerProblem.RecoveryFailed
            status.isNativeInteractionRequired -> WorkerProblem.InteractionRequired
            status.pendingDecisions > 0 -> WorkerProblem.InteractionRequired
            else -> WorkerProblem.None
        }
        WorkerSnapshot(
            if (problem == WorkerProblem.None) WorkerPhase.Running else WorkerPhase.Waiting,
            problem,
            status.runningWorkflows,
            status.pendingDecisions,
            isHostRequired = status.isNativeInteractionRequired,
        )
    }

    override suspend fun awaitRecovery() = checkNotNull(session).awaitWorkerRecovery()

    override suspend fun shutdown(): Boolean {
        val app = graph ?: return true
        val profile = session
        graph = null
        session = null
        val shutdown = app as AppShutdownAccessors
        return shutdownDesktop(
            shutdown.shutdownParticipants,
            destroy = {},
            exit = {},
            stopScopes = shutdown::closeScopes,
            stopAdmission = { profile?.graph?.scope?.coroutineScope?.cancel() },
            processes = DesktopChildProcesses(app.dispatchers),
        ) == null
    }
}
