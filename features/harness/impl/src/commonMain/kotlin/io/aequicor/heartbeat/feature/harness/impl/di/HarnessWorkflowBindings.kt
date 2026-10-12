package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsMachineSpec
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessExecutionLane
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBindings
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsEffects
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.ProfileWorkflowDrivers
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowCodeExecutor
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowHelperJournal
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowHelperPorts
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowHelperResources
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowLibraryEpoch
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowRunAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowRunExecution
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Lazy feedback references break the machine/driver cycle; author work uses the same lane as script callbacks. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object HarnessWorkflowBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun epoch(): WorkflowLibraryEpoch = WorkflowLibraryEpoch(Uuid.random().toHexString())

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun code(
        host: HarnessScriptHost,
        lane: HarnessExecutionLane,
        origins: HarnessCallOrigins,
        clock: Clock,
    ): WorkflowCodeExecutor = WorkflowCodeExecutor(host, lane, origins, clock) { it.encodeUtf8().sha256().hex() }

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun resources(helpers: HelperAgents, grants: WorkflowHelperJournal): WorkflowHelperResources =
        WorkflowHelperResources(helpers, grants)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun execution(
        storage: HarnessRunStorage,
        code: WorkflowCodeExecutor,
        grants: WorkflowHelperJournal,
        resources: WorkflowHelperResources,
        helpers: HelperAgents,
        bindings: HarnessHelperBindings,
        access: WorkflowRunAccess,
        clock: Clock,
        machine: Lazy<HarnessRunsMachine>,
    ): WorkflowRunExecution = WorkflowRunExecution(
        storage,
        code,
        WorkflowHelperPorts(grants, resources, helpers, bindings),
        access,
        clock,
    ) { machine.value.send(it) }

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun drivers(
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        execution: WorkflowRunExecution,
        machine: Lazy<HarnessRunsMachine>,
    ): ProfileWorkflowDrivers = ProfileWorkflowDrivers(scope.coroutineScope, execution) { id, generation ->
        (machine.value.state.value as? HarnessRunsState.Ready)?.runs?.any {
            it.id == id && it.driverGeneration == generation && it.status == WorkflowStatus.Running
        } == true
    }

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        storage: HarnessRunStorage,
        drivers: ProfileWorkflowDrivers,
        execution: WorkflowRunExecution,
        clock: Clock,
    ): HarnessRunsMachine = launcher.launch(
        HarnessRunsMachineSpec,
        scope,
        HarnessRunsEffects(storage, drivers, execution, clock),
    )
}
