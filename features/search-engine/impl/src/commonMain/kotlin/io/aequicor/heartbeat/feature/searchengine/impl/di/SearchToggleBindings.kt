package io.aequicor.heartbeat.feature.searchengine.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools

/** App-wide registration of the disabled-by-default search tools switch. */
@BindingContainer
@ContributesTo(AppScope::class)
public object SearchToggleBindings {
    /** Registers [SearchEngineTools] for the toggles panel. */
    @Provides
    @IntoSet
    public fun engineTools(): FeatureToggle<*> = SearchEngineTools
}
