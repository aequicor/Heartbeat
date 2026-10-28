package io.aequicor.heartbeat.feature.githubconnector.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorEnabled

/**
 * Registers the switch for the toggles panel. The feature is desktop-only: its graph, route and store live in
 * `jvmMain`, the owner on desktop is the `engines` bundle and mobile graphs do not include this module.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object GitHubConnectorToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun enabled(): FeatureToggle<*> = GitHubConnectorEnabled
}
