package io.aequicor.heartbeat.bundles.web

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import io.aequicor.heartbeat.core.bundles.FoundationProfileAgentBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileEngineBindings
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviews
import io.aequicor.heartbeat.feature.browser.api.BrowserDocuments

/**
 * What the profile graph of the `web` bundle publishes to the foundation of the same profile. Its profile startups
 * go out through `ProfileStartupAccess`, which the graph implements as a contribution of the profile scope.
 */
public interface WebProfileExports {
    /** Documents that features such as service-as-ui show in the embedded browser. */
    public val documents: BrowserDocuments

    /** Rendering of document previews for agent tools. */
    public val documentPreviews: BrowserDocumentPreviews

    /** Navigation entries of the browser; the foundation merges them into the profile routes. */
    @ForScope(ProfileScope::class)
    public val profileRoutes: Set<RouteEntry<*>>

    /** Agent tools of the embedded and the external browser; the foundation merges them into its own set. */
    public val agentTools: Set<AgentToolContribution>
}

/** Profile-level graph of the `web` bundle: the profile contributions of its member features. */
@GraphExtension(ProfileScope::class)
internal interface WebProfileGraph : WebProfileExports {
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationProfileBindings,
            @Includes engines: FoundationProfileEngineBindings,
            @Includes agents: FoundationProfileAgentBindings,
        ): WebProfileGraph
    }
}
