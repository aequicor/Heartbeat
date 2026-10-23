package io.aequicor.heartbeat.bundles.engines

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.FoundationProfileAgentBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileToolBindings
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthChecks
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.spi.AuthCredentials
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeAuthentication
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDefaults
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeInteractionDemand
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptResourceHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspaceProvider
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

    /** Web search that research runs and the coding engines use. */
    public val searchEngine: SearchEngine

    /** Security mode of the profile's agents. */
    public val agentSecuritySettings: AgentSecuritySettings

    /** Whether a native engine waits for a person at the machine. */
    public val nativeInteractionDemand: NativeInteractionDemand

    /** Default choice of engine for new routes. */
    public val engineDefaults: EngineDefaults

    /**
     * Lazy registrations of every engine adapter of the profile, the bundle's own and those of other bundles. A list,
     * not a set: the bundle takes the registrations of other bundles in [EnginesProfileImports], and a bundle never
     * both imports and exports one set.
     */
    public val registeredEngines: List<EngineRegistration>

    /** Koog connection metadata. */
    public val koogConnections: KoogConnections

    /** Explicit account probe of Claude Code. */
    public val claudeAuthentication: ClaudeAuthentication

    /** Pi workspace configuration. */
    public val piEngine: PiEngine

    /** Sources of engine authentication. */
    public val authSources: AuthSources

    /** Checks of the engine authentication sources. */
    public val authChecks: AuthChecks

    /** Model choices of the profile's connections. */
    public val modelSelections: ModelSelections

    /** Management of the engines: install, enable, configure. */
    public val engineManagement: EngineManagement

    /** Local projects the engines work in. */
    public val localWorkspaces: LocalWorkspaces

    /** The tool catalog and dispatcher of the profile's agents. */
    public val profileAgentTools: ProfileAgentTools

    /** Bridge that lets native engines call the hosted tools. */
    public val agentToolBridge: AgentToolBridge

    /** Isolated one-shot prompts of the facade. */
    public val isolatedPrompts: IsolatedPrompts

    /** Durable originals of prompt resources. */
    public val promptResourceHistory: PromptResourceHistory

    /**
     * Every agent tool contribution of the profile, whichever bundle compiled it. A list, not a set: the foundation
     * does not merge it into its own set (the facade already reads that set).
     */
    public val agentToolContributions: List<AgentToolContribution>

    /** Reads the credentials of the profile's authentication sources. */
    public val authCredentials: AuthCredentials

    /** Settings of the engines that the control panel edits. */
    public val engineConfiguration: EngineConfiguration
}

/** What the profile graph of the `engines` bundle takes from the profile graphs of the other bundles. */
public interface EnginesProfileImports {
    /** Agent tools of the other bundles (the browsers of `web`); the facade collects them with its own. */
    public val agentTools: Set<AgentToolContribution>

    /** Engine adapters that other bundles bring; the facade registers them with its own. */
    public val engineRegistrations: Set<EngineRegistration>

    /** Session hooks of the other bundles. */
    public val sessionHooks: Set<SessionHook>

    /** Tool policy providers of the other bundles. */
    public val toolPolicyProviders: Set<AgentToolPolicyProvider>

    /** Context policy providers of the other bundles. */
    public val contextPolicyProviders: Set<ContextPolicyProvider>

    /** Tool output offloads of the other bundles. */
    public val toolOutputOffloads: Set<ToolOutputOffload>

    /** Native action analyzers of the other bundles. */
    public val nativeActionAnalyzers: Set<NativeActionAnalysis>

    /** Virtual workspace providers of the other bundles. */
    public val virtualWorkspaceProviders: Set<VirtualWorkspaceProvider>
}

/**
 * Profile-level graph of the `engines` bundle: the profile contributions of its member features. The parts of the
 * agent runtime sets that the foundation compiles come in `@Provides` [FoundationProfileToolBindings] and are added
 * by [EnginesAgentRuntimeSets]; `@Includes` would replace the graph's own multibindings with them.
 */
@GraphExtension(ProfileScope::class)
internal interface EnginesProfileGraph : EnginesProfileExports {
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationProfileBindings,
            @Includes agents: FoundationProfileAgentBindings,
            @Provides tools: FoundationProfileToolBindings,
            @Provides imports: EnginesProfileImports,
        ): EnginesProfileGraph
    }
}
