package io.aequicor.heartbeat.feature.aistudio.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityMachineSpec
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityEffects
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityMachine
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityStore
import kotlinx.coroutines.launch

/** One settings editor per profile; execution reads committed profile policy independently of this UI state. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object StudioSecurityBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) profile: ScopeHandle,
        settings: StudioSecurityStore,
    ): StudioSecurityMachine = launcher.launch(
        StudioSecurityMachineSpec,
        profile,
        StudioSecurityEffects(settings),
    ).also { machine ->
        profile.coroutineScope.launch { machine.send(StudioSecurityIntent.Public.Start) }
    }
}
