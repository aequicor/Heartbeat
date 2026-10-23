package io.aequicor.heartbeat.bundles.studio

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileEngineBindings
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView

/**
 * What the profile graph of the `studio` bundle publishes to the foundation of the same profile. Its profile
 * startups go out through `ProfileStartupAccess`, which the graph implements as a contribution of the profile scope.
 */
public interface StudioProfileExports {
    /** Effort levels the profile offers per model; the studio of the foundation reads them. */
    public val effortChoices: EffortChoicesView

    /** Plans of the profile's chats; the studio of the foundation reads them. */
    public val planningView: PlanningView

    /** Agent tools of the bundle's features; `engines` merges them into the set its facade assembles. */
    public val agentTools: Set<AgentToolContribution>

    /** Session hooks of the bundle's features; `engines` merges them into the set its facade runs. */
    public val sessionHooks: Set<SessionHook>
}

/** Profile-level graph of the `studio` bundle: the profile contributions of its member features. */
@GraphExtension(ProfileScope::class)
internal interface StudioProfileGraph : StudioProfileExports {
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationProfileBindings,
            @Includes engines: FoundationProfileEngineBindings,
        ): StudioProfileGraph
    }
}
