package io.aequicor.heartbeat.bundles.agentruntime

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileEngineBindings
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspaceProvider
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection

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

    /** Sections of the project configuration tree; the foundation merges them into its own set. */
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
