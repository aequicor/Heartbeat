package io.aequicor.heartbeat.bundles.agentruntime

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileRuntimeBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileStudioBindings
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesView
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthChecks
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeInteractionDemand
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspaceProvider
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsIntent
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsOutput
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsState
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
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
 * What the profile graph of the `agent-runtime` bundle publishes to the foundation of the same profile. Its profile
 * startups go out through `ProfileStartupAccess`, which the graph implements as a contribution of the profile scope.
 */
public interface AgentRuntimeProfileExports {
    /** Navigation entries of the agent screens; the foundation merges them into the profile routes. */
    @ForScope(ProfileScope::class)
    public val profileRoutes: Set<RouteEntry<*>>

    /** Library of model descriptions of the adaptive subagents. */
    public val modelProfiles: ModelProfilesView

    /** Workflow library machine: the worker's recovery and observation read it. */
    public val harnessLibrary: Machine<HarnessState, HarnessIntent, HarnessOutput>

    /** Workflow runs machine. */
    public val harnessRuns: Machine<HarnessRunsState, HarnessRunsIntent, HarnessRunsOutput>

    /** Process decisions machine. */
    public val processDecisions: Machine<ProcessDecisionsState, ProcessDecisionsIntent, ProcessDecisionsOutput>

    /** Bus of the scheduler: the checklist and the studio post and read wake-ups through it. */
    public val schedulerBus: SchedulerBus

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

    /**
     * Helper prompt owners that the studio of the foundation reads, the bundle's own and those of other bundles. A
     * list, not a set: the bundle takes the owners of other bundles in [AgentRuntimeProfileImports], and a bundle never
     * both imports and exports one set.
     */
    public val helperPromptOwners: List<ScheduledHelperPromptOwner>

    /** Request origin observers that the studio of the foundation reads; a list for the same reason. */
    public val requestOriginObservers: List<ScheduledRequestOriginObserver>

    /** Every section of the project configuration, whichever bundle compiled it; a list: the foundation owns a set. */
    public val configSectionList: List<ConfigSection>

    /** Helper agents of the scheduler. */
    public val helperAgents: HelperAgents

    /** Providers of service-as-ui methods that the foundation's service-as-ui calls. */
    public val serviceUiProviders: Set<ServiceUiProvider>
}

/**
 * Engine services the `agent-runtime` bundle uses; the graph takes them from the `engines` bundle through `@Includes`.
 * Plain services only: a set accessor included into the graph would replace the graph's own multibinding.
 */
public interface AgentRuntimeEngineServices {
    /** Sessions and models of the AI engines. */
    public val engineFacade: EngineFacade

    /** Web search. */
    public val searchEngine: SearchEngine

    /** Security mode of the profile's agents. */
    public val agentSecuritySettings: AgentSecuritySettings

    /** Whether a native engine waits for a person at the machine. */
    public val nativeInteractionDemand: NativeInteractionDemand

    /** Sources of engine authentication. */
    public val authSources: AuthSources

    /** Checks of the engine authentication sources. */
    public val authChecks: AuthChecks

    /** Model choices of the profile's connections. */
    public val modelSelections: ModelSelections

    /** Local projects the engines work in. */
    public val localWorkspaces: LocalWorkspaces

    /** The tool catalog and dispatcher of the profile's agents. */
    public val profileAgentTools: ProfileAgentTools

    /** Isolated one-shot prompts of the facade. */
    public val isolatedPrompts: IsolatedPrompts
}

/**
 * What the profile graph of the `agent-runtime` bundle takes from the profile graphs of the other bundles: the engine
 * services (included into the graph) and, as `@Provides` instance, the parts of its sets that other bundles bring.
 */
public interface AgentRuntimeProfileImports : AgentRuntimeEngineServices {
    /** Helper prompt owners that other bundles bring to the scheduler. */
    public val scheduledHelperPromptOwners: Set<ScheduledHelperPromptOwner>

    /** Request origin observers that other bundles bring to the scheduler. */
    public val scheduledRequestOriginObservers: Set<ScheduledRequestOriginObserver>

    /** Hosts of scheduled sessions that other bundles bring to the scheduler. */
    public val scheduledSessionHosts: Set<ScheduledSessionHost>

    /** Owners of scheduled wake-ups that other bundles bring to the scheduler. */
    public val scheduledWakeOwners: Set<ScheduledWakeOwner>

    /** Sections of the project configuration that other bundles bring. */
    public val configSections: Set<ConfigSection>
}

/** Profile-level graph of the `agent-runtime` bundle: the profile contributions of its member features. */
@GraphExtension(ProfileScope::class)
internal interface AgentRuntimeProfileGraph : AgentRuntimeProfileExports {
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationProfileBindings,
            @Includes studio: FoundationProfileStudioBindings,
            @Includes engines: AgentRuntimeEngineServices,
            @Provides imports: AgentRuntimeProfileImports,
            @Provides foundationSets: FoundationProfileRuntimeBindings,
        ): AgentRuntimeProfileGraph
    }
}
