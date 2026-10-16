package io.aequicor.heartbeat.feature.applogs.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.applogs.api.AppLogsEnabled

/** Common registration because settings observes the switch before filtering by host availability. */
@ContributesTo(AppScope::class)
@BindingContainer
object AppLogsToggleBindings {
    /** Contributes to the exact wildcard set consumed by the registry. */
    @Provides
    @IntoSet
    fun enabled(): FeatureToggle<*> = AppLogsEnabled
}
