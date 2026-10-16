package io.aequicor.heartbeat.feature.projectconfig.impl.di

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
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigEnabled
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigMachineSpec
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.projectconfig.impl.data.ProjectConfigEffects
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ProjectConfigMachine

/** The profile machine of project configuration; it starts with the profile ([ProjectConfigStartup]). */
@ContributesTo(ProfileScope::class)
@BindingContainer
object ProjectConfigBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        effects: ProjectConfigEffects,
    ): ProjectConfigMachine = launcher.launch(ProjectConfigMachineSpec, scope, effects)
}

/** Sections of the configuration tree; empty until features contribute them. */
@ContributesTo(ProfileScope::class)
interface ProjectConfigMultibindings {
    /** Sections by feature: harnesses, interfaces, learning. */
    @Multibinds(allowEmpty = true)
    fun configSections(): Set<ConfigSection>
}

/** Registers the feature flag in the toggle panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object ProjectConfigToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun enabled(): FeatureToggle<*> = ProjectConfigEnabled
}
