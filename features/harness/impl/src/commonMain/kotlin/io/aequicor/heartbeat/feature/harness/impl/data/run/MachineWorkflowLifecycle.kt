package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
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
        val ready = journal(machine) ?: return false
        val selected = ready.runs.filter { it.isOwnedBy(effect) }
        return if (effect.isStopping) {
            selected.forEach {
                val request = RequestId(Uuid.random().toHexString())
                machine.send(HarnessRunsIntent.Public.Cancel(request, it.id, WorkflowViewer.User))
            }
            selected.isEmpty()
        } else {
            selected.forEach { drivers.value.pauseCurrent(it) }
            selected.all { drivers.value.isPaused(it.id, it.driverGeneration) }
        }
    }

    /** Starts restoring an absent journal; deactivation is retried once it is Ready. */
    private suspend fun journal(machine: HarnessRunsMachine): HarnessRunsState.Ready? {
        val state = machine.state.value
        if (state is HarnessRunsState.Idle || state is HarnessRunsState.Failed) {
            machine.send(HarnessRunsIntent.Internal.Start)
        }
        return machine.state.value as? HarnessRunsState.Ready
    }

    /** Runs admitted by the deactivated library generation, or by an earlier process. */
    private fun WorkflowRun.isOwnedBy(effect: HarnessEffect.Deactivate): Boolean {
        val routing = routing
        return harness in effect.harnesses && status == WorkflowStatus.Running &&
            (routing?.libraryEpoch != epoch.value || routing.libraryGeneration <= effect.generation)
    }
}
