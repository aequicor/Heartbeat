package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.core.profilefacade.awaitStartupReady
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ActiveSessionRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FacadeContext
import io.aequicor.heartbeat.feature.harness.api.HarnessBackgroundWorker
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsIntent
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsOutput
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsState
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Safe worker projection. No profile identifiers, request bodies or approval-capable handles cross the boundary. */
public data class WorkerRuntimeStatus(
    public val hasFailed: Boolean,
    public val isNativeInteractionRequired: Boolean,
    public val runningWorkflows: Int,
    public val pendingDecisions: Int,
)

/** App host access, resolved only after acquiring the storage lease. */
@ContributesTo(AppScope::class)
public interface WorkerApplicationAccess {
    val toggles: FeatureToggles
}

/** Profile recovery and observation ports assembled by the host bundle. */
@ContributesTo(ProfileScope::class)
public interface WorkerProfileAccess {
    val secrets: SecretStore
    val context: FacadeContext
    val handles: ActiveSessionRegistry
    val library: Machine<HarnessState, HarnessIntent, HarnessOutput>
    val runs: Machine<HarnessRunsState, HarnessRunsIntent, HarnessRunsOutput>
    val decisions: Machine<ProcessDecisionsState, ProcessDecisionsIntent, ProcessDecisionsOutput>
}

/** Read only the registered opt-in flag; this must happen after the host owns the storage root. */
public suspend fun HeartbeatGraph.isBackgroundWorkerEnabled(): Boolean =
    (this as WorkerApplicationAccess).toggles.get(HarnessBackgroundWorker)

/**
 * Strict hosts wait for actual initial recovery barriers. Services can continue native cleanup afterwards;
 * this does not assert that all external effects have settled. The host supplies an overall timeout.
 */
public suspend fun ProfileSession.awaitWorkerRecovery() {
    check(startupFailures == 0) { "Profile startup failed" }
    val access = graph as WorkerProfileAccess
    access.secrets.keys()
    awaitStartupReady()
    access.decisions.send(ProcessDecisionsIntent.Public.Load)
    check(
        access.decisions.state.first {
            it is ProcessDecisionsState.Ready || it is ProcessDecisionsState.Failed
        } is ProcessDecisionsState.Ready,
    ) { "Process decision recovery failed" }
}

/** Observe authoritative native requests, including sessions that do not opt into session hooks. */
public fun ProfileSession.workerRuntimeStatus(): Flow<WorkerRuntimeStatus> {
    val access = graph as WorkerProfileAccess
    return combine(
        access.library.state,
        access.runs.state,
        access.decisions.state,
        access.handles.awaitingInteraction,
        access.context.authenticationRequired,
    ) { library, runs, decisions, interaction, authentication ->
        WorkerRuntimeStatus(
            hasFailed = library is HarnessState.Failed || runs is HarnessRunsState.Failed ||
                decisions is ProcessDecisionsState.Failed,
            isNativeInteractionRequired = interaction || authentication,
            runningWorkflows =
                (runs as? HarnessRunsState.Ready)?.runs?.count { it.status == WorkflowStatus.Running } ?: 0,
            pendingDecisions = (decisions as? ProcessDecisionsState.Ready)?.ledger?.entries?.count {
                it.decision.phase == ProcessDecisionPhase.Pending
            } ?: 0,
        )
    }
}
