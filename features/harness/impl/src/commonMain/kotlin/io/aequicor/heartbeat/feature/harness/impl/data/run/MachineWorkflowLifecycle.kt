package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowViewer
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessWorkflowLifecycle
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.ProfileWorkflowDrivers
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowLibraryEpoch
import kotlin.uuid.Uuid

/** Library deletion waits for terminal journals; global suspension waits for the driver's native stop barrier. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineWorkflowLifecycle(
    private val runs: Lazy<HarnessRunsMachine>,
    private val library: Lazy<HarnessMachine>,
    private val drivers: Lazy<ProfileWorkflowDrivers>,
    private val epoch: WorkflowLibraryEpoch,
) : HarnessWorkflowLifecycle {
    override suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean {
        if (effect.harnesses.isEmpty()) return true
        val snapshot = library.value.state.value
        if (!effect.isStopping && !snapshot.isSuspended) return true
        val machine = runs.value
        if (snapshot.isSuspended) machine.send(HarnessRunsIntent.Internal.Suspended)
        when (machine.state.value) {
            is HarnessRunsState.Idle, is HarnessRunsState.Failed -> {
                machine.send(HarnessRunsIntent.Internal.Start)
                return false
            }
            is HarnessRunsState.Loading -> return false
            is HarnessRunsState.Ready -> Unit
        }
        val ready = machine.state.value as? HarnessRunsState.Ready ?: return false
        val selected = ready.runs.filter {
            val routing = it.routing
            it.harness in effect.harnesses && it.status == WorkflowStatus.Running &&
                (routing?.libraryEpoch != epoch.value || routing.libraryGeneration <= effect.generation)
        }
        if (!effect.isStopping) {
            selected.forEach { drivers.value.pauseCurrent(it) }
            return selected.all { drivers.value.isPaused(it.id, it.driverGeneration) }
        }
        for (run in selected) {
            machine.send(
                HarnessRunsIntent.Public.Cancel(RequestId(Uuid.random().toHexString()), run.id, WorkflowViewer.User),
            )
        }
        return selected.isEmpty()
    }
}
