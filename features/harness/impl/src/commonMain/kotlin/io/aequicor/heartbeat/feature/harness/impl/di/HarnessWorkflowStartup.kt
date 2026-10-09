package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowViewer
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.ProfileWorkflowDrivers
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowHelperJournal
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Starts on first enable, retains journals on suspension, and cancels late starts of disabled/deleted harnesses. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessWorkflowStartup(
    private val machine: Lazy<HarnessRunsMachine>,
    private val library: Lazy<HarnessMachine>,
    private val toggles: FeatureToggles,
    private val grants: Lazy<WorkflowHelperJournal>,
    private val drivers: Lazy<ProfileWorkflowDrivers>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    override fun start() {
        profile.coroutineScope.launch {
            toggles.observe(HarnessEnabled).distinctUntilChanged().collectLatest { enabled ->
                if (enabled) followEnabled() else followSuspended()
            }
        }
    }

    /** Resumes the journal and cancels late runs of harnesses that were disabled or are being deleted. */
    private suspend fun followEnabled() {
        val runs = machine.value
        try {
            runs.send(HarnessRunsIntent.Internal.Resumed)
            if (runs.state.value is HarnessRunsState.Idle || runs.state.value is HarnessRunsState.Failed) {
                runs.send(HarnessRunsIntent.Internal.Start)
            }
            combine(runs.state, library.value.state) { current, content -> orphaned(current, content) }
                .collect { orphans ->
                    orphans.forEach { run ->
                        val request = RequestId(Uuid.random().toHexString())
                        runs.send(HarnessRunsIntent.Public.Cancel(request, run.id, WorkflowViewer.User))
                    }
                }
        } finally {
            withContext(NonCancellable) { runs.send(HarnessRunsIntent.Internal.Suspended) }
        }
    }

    /** Preexisting granted slots must drain even when the profile opens with the feature disabled. */
    private suspend fun followSuspended() {
        if (!machine.isInitialized() && grants.value.pending().isEmpty()) return
        val runs = machine.value
        runs.send(HarnessRunsIntent.Internal.Suspended)
        if (runs.state.value is HarnessRunsState.Idle || runs.state.value is HarnessRunsState.Failed) {
            runs.send(HarnessRunsIntent.Internal.Start)
        }
        runs.state.collect { state ->
            val ready = state as? HarnessRunsState.Ready ?: return@collect
            if (ready.isSuspended) {
                ready.runs.filter { it.status == WorkflowStatus.Running }.forEach {
                    drivers.value.pauseCurrent(it)
                }
            }
        }
    }
}

/** Running, not yet cancelled runs whose harness is disabled, missing or being removed. */
private fun orphaned(runs: HarnessRunsState, library: HarnessState): List<WorkflowRun> {
    val state = (runs as? HarnessRunsState.Ready)?.takeUnless { it.isSuspended } ?: return emptyList()
    val ready = (library as? HarnessState.Ready)?.takeUnless { it.isSuspended } ?: return emptyList()
    val live = ready.harnesses.asSequence().map { it.harness }
        .filter { it.isEnabled && ready.pending[it.id] !is HarnessMutation.Remove }
        .map { it.id }.toSet()
    return state.runs.filter { it.status == WorkflowStatus.Running && it.cancellation == null && it.harness !in live }
}
