package io.aequicor.heartbeat.bundles.studio

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.FoundationProfileAgentBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthChecks
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDefaults
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeInteractionDemand
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptResourceHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspaceProvider
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnections
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviews
import io.aequicor.heartbeat.feature.browser.api.BrowserDocuments
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledHelperPromptOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledRequestOriginObserver
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.serviceasui.api.spi.ServiceUiProvider

/**
 * What the profile graph of the `studio` bundle publishes to the foundation of the same profile. Its profile startups
 * go out through `ProfileStartupAccess`, which the graph implements as a contribution of the profile scope.
 */
public interface StudioProfileExports {
    /** Navigation entries of the studio screens; the foundation merges them into the profile routes. */
    @ForScope(ProfileScope::class)
    public val profileRoutes: Set<RouteEntry<*>>

    /** Studio screens that the Chromium side panel opens and drives. */
    public val studioRemoteScreens: StudioRemoteScreens

    /** Catalog of the profile's attachments. */
    public val attachmentsCatalog: AttachmentsCatalog

    /** Reads the bytes of attachments and other prompt resources of the profile. */
    public val resourceResolver: ResourceResolver

    /** Agent tools; the `engines` bundle collects them with its own. */
    public val agentTools: Set<AgentToolContribution>

    /** Session hooks; collected by the `engines` bundle. */
    public val sessionHooks: Set<SessionHook>

    /** Tool policy providers; collected by the `engines` bundle. */
    public val toolPolicyProviders: Set<AgentToolPolicyProvider>

    /** Context policy providers; collected by the `engines` bundle. */
    public val contextPolicyProviders: Set<ContextPolicyProvider>

    /** Tool output offloads; collected by the `engines` bundle. */
    public val toolOutputOffloads: Set<ToolOutputOffload>

    /** Native action analyzers; collected by the `engines` bundle. */
    public val nativeActionAnalyzers: Set<NativeActionAnalysis>

    /** Virtual workspace providers; collected by the `engines` bundle. */
    public val virtualWorkspaceProviders: Set<VirtualWorkspaceProvider>

    /** Hosts of scheduled sessions; collected by the scheduler of the `agent-runtime` bundle. */
    public val scheduledSessionHosts: Set<ScheduledSessionHost>

    /** Owners of scheduled wake-ups; collected by the scheduler. */
    public val scheduledWakeOwners: Set<ScheduledWakeOwner>

    /** Sections of the project configuration; collected by the `agent-runtime` bundle. */
    public val configSections: Set<ConfigSection>
}

/**
 * Services of the other bundles the `studio` bundle uses; the graph takes them through `@Includes`. Plain services
 * only: a set accessor included into the graph would replace the graph's own multibinding.
 */
public interface StudioServices {
    /** Sessions and models of the AI engines. */
    public val engineFacade: EngineFacade

    /** Web search. */
    public val searchEngine: SearchEngine

    /** Security mode of the profile's agents. */
    public val agentSecuritySettings: AgentSecuritySettings

    /** Whether a native engine waits for a person at the machine. */
    public val nativeInteractionDemand: NativeInteractionDemand

    /** Default engine of new routes. */
    public val engineDefaults: EngineDefaults

    /** Koog connection metadata. */
    public val koogConnections: KoogConnections

    /** Sources of engine authentication. */
    public val authSources: AuthSources

    /** Checks of the engine authentication sources. */
    public val authChecks: AuthChecks

    /** Model choices of the profile's connections. */
    public val modelSelections: ModelSelections

    /** Management of the engines. */
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

    /** Bus of the scheduler. */
    public val schedulerBus: SchedulerBus

    /** Helper agents of the scheduler. */
    public val helperAgents: HelperAgents

    /** Documents shown in the embedded browser of the `web` bundle. */
    public val browserDocuments: BrowserDocuments

    /** Visual checks of documents in the embedded browser. */
    public val browserDocumentPreviews: BrowserDocumentPreviews
}

/**
 * What the profile graph of the `studio` bundle takes from the profile graphs of the other bundles: the services
 * (included into the graph) and, as `@Provides` instance, the parts of its sets that other bundles bring.
 */
public interface StudioProfileImports : StudioServices {
    /** Helper prompt owners of the scheduler. */
    public val helperPromptOwners: List<ScheduledHelperPromptOwner>

    /** Request origin observers of the scheduler. */
    public val requestOriginObservers: List<ScheduledRequestOriginObserver>

    /** Providers of service-as-ui methods, mostly the harness. */
    public val serviceUiProviders: Set<ServiceUiProvider>
}

/** Profile-level graph of the `studio` bundle: the profile contributions of its member features. */
@GraphExtension(ProfileScope::class)
internal interface StudioProfileGraph : StudioProfileExports {
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationProfileBindings,
            @Includes agents: FoundationProfileAgentBindings,
            @Includes services: StudioServices,
            @Provides imports: StudioProfileImports,
        ): StudioProfileGraph
    }
}
