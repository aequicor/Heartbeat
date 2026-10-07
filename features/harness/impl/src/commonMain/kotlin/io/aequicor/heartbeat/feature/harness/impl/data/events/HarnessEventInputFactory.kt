package io.aequicor.heartbeat.feature.harness.impl.data.events

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.harness.api.event.HarnessLifecycleEvent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsMachineKey
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessSessionProofs
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlin.time.Clock

/** Creates subscriptions only on enable. Lazy facade access breaks the SessionHook/facade contribution cycle. */
@Inject
internal class HarnessEventInputFactory(
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val facade: Lazy<EngineFacade>,
    private val workspaces: Lazy<LocalWorkspaces>,
    private val bus: SchedulerBus,
    private val projects: HarnessProjectSnapshots,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun create(): HarnessEventInputs = HarnessEventInputs(
        bus.events,
        HarnessSwitchingEvents(machines.observe(SchedulerMachineKey)) { it.outputs },
        toggles.observe(SchedulerEnabled),
        facade.value.engines.state,
        facade.value.bindings.state,
        HarnessSwitchingEvents(machines.observe(HarnessRunsMachineKey)) { machine ->
            machine.outputs.mapNotNull { output ->
                val finished = output as? HarnessRunsOutput.RunFinished ?: return@mapNotNull null
                val ready = machine.state.value as? HarnessRunsState.Ready ?: return@mapNotNull null
                val run = ready.runs.find { it.id == finished.run && it.status == finished.status }
                    ?: return@mapNotNull null
                HarnessLifecycleEvent.WorkflowFinished(run.harness, run.workflow, run.id, run.status, clock.now())
            }
        },
    ) { scope -> projects.observe(scope, workspaces.value.observe()) }

    /** Caller supplies the short hook deadline. Waiting never substitutes an unknown checkout for its project. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitProof(context: SessionHookContext, proofs: HarnessSessionProofs) {
        combine(
            machines.observe(WorktreeMachineKey).flatMapLatest { it?.state ?: flowOf(null) },
            projects.projects,
        ) { _, _ -> proofs.isResolved(context.session) }.first { it }
    }
}
