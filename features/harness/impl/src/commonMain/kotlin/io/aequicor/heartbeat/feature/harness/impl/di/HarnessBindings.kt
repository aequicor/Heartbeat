package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineSpec
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessEffects
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessLibraryStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRuntimeControl

/** Constructs a single profile-owned library without eagerly starting it when the toggle is disabled. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object HarnessBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        storage: HarnessLibraryStorage,
        runtime: HarnessRuntimeControl,
    ): HarnessMachine = launcher.launch(HarnessMachineSpec, scope, HarnessEffects(storage, runtime))
}

/** Registers the disabled-by-default feature flag with its exact wildcard multibinding type. */
@ContributesTo(AppScope::class)
@BindingContainer
public object HarnessToggleBindings {
    /** Exact wildcard type contributes the flag to the application registry. */
    @Provides
    @IntoSet
    public fun enabled(): FeatureToggle<*> = HarnessEnabled
}
