package io.aequicor.heartbeat.bundles.agentruntime

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection

/**
 * Parts of the sets that `agent-runtime` owns and the foundation contributes to through [FoundationProfileBindings].
 * Every element is read when the set is requested, which happens after the profile graphs of the bundles are open.
 */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object AgentRuntimeFoundationSets {
    @Provides
    @ElementsIntoSet
    fun foundationConfigSections(foundation: FoundationProfileBindings): Set<ConfigSection> =
        foundation.agentRuntimeSets.configSections
}
