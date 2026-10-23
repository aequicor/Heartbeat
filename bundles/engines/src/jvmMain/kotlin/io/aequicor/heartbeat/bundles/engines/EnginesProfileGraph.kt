@file:Suppress("FeatureLayerDependency") // the bundle is the application side of the engine SPI

package io.aequicor.heartbeat.bundles.engines

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileModelBindings
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthChecks
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.spi.AuthCredentials
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeAuthentication
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDefaults
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeInteractionDemand
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnections
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine

/**
 * What the profile graph of the `engines` bundle publishes to the foundation of the same profile. Its profile
 * startups go out through `ProfileStartupAccess`, which the graph implements as a contribution of the profile scope.
 */
public interface EnginesProfileExports {
    /** Navigation entries of the engine screens; the foundation merges them into the profile routes. */
    @ForScope(ProfileScope::class)
    public val profileRoutes: Set<RouteEntry<*>>

    /** Sessions and models of the AI engines. */
    public val engineFacade: EngineFacade

    /** Web search that research runs use. */
    public val searchEngine: SearchEngine

    /** Authorization checks of the engine sources. */
    public val authChecks: AuthChecks

    /** Credentials of the engine sources. */
    public val authCredentials: AuthCredentials

    /** Engine configuration of the profile. */
    public val engineConfiguration: EngineConfiguration

    /** Control surface over every registered engine (installation, login, switches). */
    public val engineManagement: EngineManagement

    /** Tools of the profile's agents; the studio and harnesses of the foundation call them. */
    public val profileAgentTools: ProfileAgentTools

    /** Profile-wide switches of the agent's tools and permissions. */
    public val agentSecuritySettings: AgentSecuritySettings

    /** Sources of engine authorization. */
    public val authSources: AuthSources

    /** One-shot isolated prompts that features such as harnesses run. */
    public val isolatedPrompts: IsolatedPrompts

    /** Local projects of the profile. */
    public val localWorkspaces: LocalWorkspaces

    /** Model choices of the profile. */
    public val modelSelections: ModelSelections

    /** Whether an engine session currently needs the native desktop. */
    public val nativeInteractionDemand: NativeInteractionDemand

    /** Claude Code CLI login. */
    public val claudeAuthentication: ClaudeAuthentication

    /** Pi engine adapter. */
    public val piEngine: PiEngine

    /** Engines and models chosen by default. */
    public val engineDefaults: EngineDefaults

    /** Registrations of the engines bundled with the application. */
    public val engineRegistrations: Set<EngineRegistration>

    /** Connections to Koog providers. */
    public val koogConnections: KoogConnections
}

/** Profile-level graph of the `engines` bundle: the profile contributions of its member features. */
@GraphExtension(ProfileScope::class)
internal interface EnginesProfileGraph : EnginesProfileExports {
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationProfileBindings,
            @Includes models: FoundationProfileModelBindings,
            // Provided as an instance, not included: an included interface would bind its `Set` accessor itself and
            // shadow the multibinding the facade reads.
            @Provides imports: EnginesProfileImports,
        ): EnginesProfileGraph
    }
}
