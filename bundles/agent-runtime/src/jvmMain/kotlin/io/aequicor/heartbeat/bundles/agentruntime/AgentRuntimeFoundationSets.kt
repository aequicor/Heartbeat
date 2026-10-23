package io.aequicor.heartbeat.bundles.agentruntime

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.BundleUnavailableException
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoverySource
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledEventOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.SchedulerEventSource

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

    @Provides
    @ElementsIntoSet
    fun foundationSessionHosts(foundation: FoundationProfileBindings): Set<ScheduledSessionHost> =
        foundation.agentRuntimeSets.scheduledSessionHosts

    @Provides
    @ElementsIntoSet
    fun foundationWakeOwners(foundation: FoundationProfileBindings): Set<ScheduledWakeOwner> =
        foundation.agentRuntimeSets.scheduledWakeOwners

    @Provides
    @ElementsIntoSet
    fun bundleWakeOwners(imports: AgentRuntimeProfileImports): Set<ScheduledWakeOwner> = imports.scheduledWakeOwners

    @Provides
    @ElementsIntoSet
    fun studioConfigSections(imports: AgentRuntimeProfileImports): Set<ConfigSection> = try {
        imports.studioConfigSections.configSections
    } catch (e: BundleUnavailableException) {
        Log.tag("AgentRuntimeSets").w(e) { "The studio bundle is unavailable; its configuration sections stay out" }
        emptySet()
    }

    @Provides
    @ElementsIntoSet
    fun foundationEventOwners(foundation: FoundationProfileBindings): Set<ScheduledEventOwner> =
        foundation.agentRuntimeSets.scheduledEventOwners

    @Provides
    @ElementsIntoSet
    fun foundationRecoverySources(foundation: FoundationProfileBindings): Set<HelperCapacityRecoverySource> =
        foundation.agentRuntimeSets.helperCapacityRecoverySources

    @Provides
    @ElementsIntoSet
    fun foundationEventSources(foundation: FoundationProfileBindings): Set<SchedulerEventSource> =
        foundation.agentRuntimeSets.schedulerEventSources
}
