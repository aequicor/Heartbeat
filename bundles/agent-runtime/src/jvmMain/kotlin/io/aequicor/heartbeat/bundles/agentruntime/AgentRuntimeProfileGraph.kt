package io.aequicor.heartbeat.bundles.agentruntime

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileEngineBindings
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveModels
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesView
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspaceProvider
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommands
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus

/**
 * What the profile graph of the `agent-runtime` bundle publishes to the foundation of the same profile. Its profile
 * startups go out through `ProfileStartupAccess`, which the graph implements as a contribution of the profile scope.
 */
public interface AgentRuntimeProfileExports {
    /** Navigation entries of the bundle's screens; the foundation merges them into the profile routes. */
    @ForScope(ProfileScope::class)
    public val profileRoutes: Set<RouteEntry<*>>

    /** Agent tools of the bundle's features; `engines` merges them into the set its facade assembles. */
    public val agentTools: Set<AgentToolContribution>

    /** Virtual workspaces (sandboxes); `engines` merges them into the set its facade assembles. */
    public val virtualWorkspaceProviders: Set<VirtualWorkspaceProvider>

    /** Events and wakes between features; the studio and the checklists of the foundation publish through it. */
    public val schedulerBus: SchedulerBus

    /** Background agents that other features start (subagents). */
    public val backgroundAgents: BackgroundAgents

    /** Helper agents that harnesses run. */
    public val helperAgents: HelperAgents

    /** Local commands that run in the profile's projects (harness scripts and checks). */
    public val localCommands: LocalCommands

    /** Library of model descriptions; the foundation and the `engines` bundle (through the foundation) read it. */
    public val modelProfiles: ModelProfilesView

    /** Picks models for subagents and tasks. */
    public val adaptiveModels: AdaptiveModels

    /**
     * Every section of the project configuration tree, the foundation's own included. A read-only view: the bundle
     * owns the set, so the foundation never merges it back.
     */
    public val configSections: Set<ConfigSection>
}

/** Profile-level graph of the `agent-runtime` bundle: the profile contributions of its member features. */
@GraphExtension(ProfileScope::class)
internal interface AgentRuntimeProfileGraph : AgentRuntimeProfileExports {
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationProfileBindings,
            @Includes engines: FoundationProfileEngineBindings,
        ): AgentRuntimeProfileGraph
    }
}
