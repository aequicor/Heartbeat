package io.aequicor.heartbeat.feature.githubconnector.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorEnabled

/** Common registration because settings observes the switch before filtering by host availability. */
@ContributesTo(AppScope::class)
@BindingContainer
object GitHubConnectorToggleBindings {
    /** The type must be exactly `FeatureToggle<*>` to join the registry set. */
    @Provides
    @IntoSet
    fun enabled(): FeatureToggle<*> = GitHubConnectorEnabled
}
