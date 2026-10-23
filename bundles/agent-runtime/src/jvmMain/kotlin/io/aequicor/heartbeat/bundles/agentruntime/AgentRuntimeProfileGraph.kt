package io.aequicor.heartbeat.bundles.agentruntime

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.ConfigSectionPart
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileEngineBindings
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveModels
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesView
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
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
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommands
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledHelperPromptOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledRequestOriginObserver
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import io.aequicor.heartbeat.feature.serviceasui.api.spi.ServiceUiProvider

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

    /** Harness library; the host's worker watches its state. */
    public val harnessLibrary: Machine<HarnessState, HarnessIntent, HarnessOutput>

    /** Workflow runs of the harnesses; the host's worker watches their state. */
    public val harnessRuns: Machine<HarnessRunsState, HarnessRunsIntent, HarnessRunsOutput>

    /** Decisions on process commands; the host's worker recovers and watches them. */
    public val processDecisions: Machine<ProcessDecisionsState, ProcessDecisionsIntent, ProcessDecisionsOutput>

    /** Session hooks of the bundle's features; `engines` merges them into the set its facade runs. */
    public val sessionHooks: Set<SessionHook>

    /** Prompt context owners of the bundle's harnesses; the studio of the foundation reads them. */
    public val scheduledHelperPromptOwners: Set<ScheduledHelperPromptOwner>

    /** Request provenance observers of the bundle's harnesses; the studio of the foundation reads them. */
    public val scheduledRequestOriginObservers: Set<ScheduledRequestOriginObserver>

    /** Providers of service-as-ui methods and owners; the `studio` bundle, which owns that set, merges them. */
    public val serviceUiProviders: Set<ServiceUiProvider>

    /**
     * Every section of the project configuration tree, the foundation's own included. A read-only view: the bundle
     * owns the set, so the foundation never merges it back.
     */
    public val configSections: Set<ConfigSection>
}

/**
 * What the profile graph of `agent-runtime` takes from other bundles; implemented by the host with lazy proxies.
 * Sets of this interface are owned by `agent-runtime` (its scheduler assembles them), so the bundle never exports
 * them.
 */
public interface AgentRuntimeProfileImports {
    /** Admission controllers of the `studio` bundle's features (checklists) for the wakes they own. */
    public val scheduledWakeOwners: Set<ScheduledWakeOwner>

    /**
     * Project configuration sections of the `studio` bundle's features (the interface section of service-as-ui). A
     * holder: this bundle exports the whole `Set<ConfigSection>`, so a part cannot come under the same key.
     */
    public val studioConfigSections: ConfigSectionPart
}

/** Profile-level graph of the `agent-runtime` bundle: the profile contributions of its member features. */
@GraphExtension(ProfileScope::class)
internal interface AgentRuntimeProfileGraph : AgentRuntimeProfileExports {
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationProfileBindings,
            @Includes engines: FoundationProfileEngineBindings,
            // Provided as an instance, not included: an included interface would bind its `Set` accessor itself and
            // shadow the multibinding the scheduler reads.
            @Provides imports: AgentRuntimeProfileImports,
        ): AgentRuntimeProfileGraph
    }
}
