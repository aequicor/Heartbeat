package io.aequicor.heartbeat.feature.welcome.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.welcome.api.CinematicIntro

/** Public Metro contributions collected by the application bundle. */
@ContributesTo(AppScope::class)
@BindingContainer
object WelcomeToggleBindings {
    /** Registers the cinematic preference in the app-wide toggle catalog. */
    @Provides
    @IntoSet
    fun cinematicIntro(): FeatureToggle<*> = CinematicIntro
}
