package io.aequicor.heartbeat.core.featuretoggles.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

// Contributed interfaces must be public: the graph that implements them is generated in :platform-main:di-bundle.
// core:feature-toggles:impl is visible only to the bundle, so this does not widen the API for features.

/** Declares the set of registered toggles: features add to it with `@Provides @IntoSet` in `AppScope`. */
@ContributesTo(AppScope::class)
public interface FeatureToggleRegistryBindings {
    /** Every registered toggle; empty until a feature contributes one. */
    @Multibinds(allowEmpty = true)
    public fun registeredToggles(): Set<FeatureToggle<*>>
}
