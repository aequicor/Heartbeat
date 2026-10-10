package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRuntimeControl
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessExecutionLane
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessLibraryAdmission
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntime
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntimeContextFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntimeEnvironment
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.ProfileHarnessRuntimeControl
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnOperations
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessWorkflowLifecycle

/** Constructs execution without starting it; lazy machine access breaks the library/runtime feedback cycle. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object HarnessRuntimeBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun environment(
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        dispatchers: DispatcherProvider,
        contexts: HarnessRuntimeContextFactory,
        machine: Lazy<HarnessMachine>,
    ): HarnessRuntimeEnvironment = HarnessRuntimeEnvironment(
        scope.coroutineScope,
        dispatchers,
        HarnessLibraryAdmission { machine.value.state.value },
        contexts,
    ) { machine.value.send(it) }

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun lane(dispatchers: DispatcherProvider): HarnessExecutionLane = HarnessExecutionLane(dispatchers.io)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun runtime(
        host: HarnessScriptHost,
        lane: HarnessExecutionLane,
        environment: HarnessRuntimeEnvironment,
    ): HarnessRuntime = HarnessRuntime(host, lane, environment)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun control(
        runtime: HarnessRuntime,
        runs: HarnessRunStorage,
        spawns: HarnessSpawnOperations,
        workflows: Lazy<HarnessWorkflowLifecycle>,
    ): HarnessRuntimeControl = ProfileHarnessRuntimeControl(
        runtime,
        runs,
        HarnessWorkflowLifecycle { workflows.value.deactivate(it) },
        spawns,
    )
}
