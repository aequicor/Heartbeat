package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsMachineSpec
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionEffects
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionHost
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionsMachine
import kotlin.time.Clock

/** A private host-owned machine, loaded lazily by the process command service or native decision screen. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object ProcessDecisionBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun host(machine: ProcessDecisionsMachine, clock: Clock): ProcessDecisionHost =
        ProcessDecisionHost(machine, clock)

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        storage: ProcessDecisionStorage,
    ): ProcessDecisionsMachine = launcher.launch(ProcessDecisionsMachineSpec, scope, ProcessDecisionEffects(storage))
}
