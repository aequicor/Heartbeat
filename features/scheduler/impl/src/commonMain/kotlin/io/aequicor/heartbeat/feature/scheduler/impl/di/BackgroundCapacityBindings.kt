package io.aequicor.heartbeat.feature.scheduler.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityMachineSpec
import io.aequicor.heartbeat.feature.scheduler.impl.data.BackgroundCapacityMachine

/** One admission machine is shared by every background execution entry point in the profile. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object BackgroundCapacityBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun capacity(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) profile: ScopeHandle,
    ): BackgroundCapacityMachine = launcher.launch(BackgroundCapacityMachineSpec, profile, EffectHandler.None)
}
