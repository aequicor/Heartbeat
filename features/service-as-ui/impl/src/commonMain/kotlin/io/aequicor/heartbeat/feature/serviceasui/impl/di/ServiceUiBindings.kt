package io.aequicor.heartbeat.feature.serviceasui.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiMachineSpec
import io.aequicor.heartbeat.feature.serviceasui.api.spi.ServiceUiProvider
import io.aequicor.heartbeat.feature.serviceasui.impl.data.ServiceUiEffects
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiMachine
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiWeb
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiWebSafeMode

/** The profile machine of agent interfaces; it survives screens and starts with the profile. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object ServiceUiBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        effects: ServiceUiEffects,
    ): ServiceUiMachine = launcher.launch(ServiceUiMachineSpec, scope, effects)
}

/** Surface owners such as harnesses; empty until a feature contributes a provider. */
@ContributesTo(ProfileScope::class)
interface ServiceUiMultibindings {
    /** Providers of owners and their methods. */
    @Multibinds(allowEmpty = true)
    fun serviceUiProviders(): Set<ServiceUiProvider>
}

/** Registers the feature flag in the toggle panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object ServiceUiToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun enabled(): FeatureToggle<*> = ServiceUiEnabled

    /** Web documents in surfaces (desktop), off by default. */
    @Provides
    @IntoSet
    fun web(): FeatureToggle<*> = ServiceUiWeb

    /** External resources stay blocked unless the user opts out in the persisted app-wide toggle panel. */
    @Provides
    @IntoSet
    fun webSafeMode(): FeatureToggle<*> = ServiceUiWebSafeMode
}
