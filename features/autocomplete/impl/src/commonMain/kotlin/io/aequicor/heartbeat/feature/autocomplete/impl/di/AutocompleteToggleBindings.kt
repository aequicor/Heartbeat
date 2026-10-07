package io.aequicor.heartbeat.feature.autocomplete.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.autocomplete.api.AutocompleteEnabled

/** Registers the autocomplete toggle in the toggles panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object AutocompleteToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun autocomplete(): FeatureToggle<*> = AutocompleteEnabled
}
