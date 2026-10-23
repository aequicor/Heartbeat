package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.bundles.impl.BundleHost
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesView
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsIntent
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsOutput
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsState
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledHelperPromptOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledRequestOriginObserver
import io.aequicor.heartbeat.feature.serviceasui.api.spi.ServiceUiProvider

/**
 * What the profile graph of the foundation uses of the `agent-runtime` bundle; implemented by the host with lazy
 * proxies. Without the bundle there is no agent runtime to fall back to, so an unavailable bundle surfaces as
 * `BundleUnavailableException` where a service is first used.
 */
internal interface FoundationAgentRuntimeImports {
    /** Library of model descriptions of the adaptive subagents. */
    val modelProfiles: ModelProfilesView

    /** Workflow library machine. */
    val harnessLibrary: Machine<HarnessState, HarnessIntent, HarnessOutput>

    /** Workflow runs machine. */
    val harnessRuns: Machine<HarnessRunsState, HarnessRunsIntent, HarnessRunsOutput>

    /** Process decisions machine. */
    val processDecisions: Machine<ProcessDecisionsState, ProcessDecisionsIntent, ProcessDecisionsOutput>

    /** Bus of the scheduler. */
    val schedulerBus: SchedulerBus

    /** Helper agents of the scheduler. */
    val helperAgents: HelperAgents

    /** Helper prompt owners the studio reads. */
    val helperPromptOwners: List<ScheduledHelperPromptOwner>

    /** Request origin observers the studio reads. */
    val requestOriginObservers: List<ScheduledRequestOriginObserver>

    /** Providers of service-as-ui methods. */
    val serviceUiProviders: Set<ServiceUiProvider>
}

/** Bridges the services of the `agent-runtime` bundle into the profile graph of the foundation. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object AgentRuntimeImportBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    fun agentRuntimeImports(
        host: BundleHost,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
    ): FoundationAgentRuntimeImports = host.foundationProfileImports(FoundationAgentRuntimeImports::class, scope)

    @Provides
    fun modelProfiles(imports: FoundationAgentRuntimeImports): ModelProfilesView = imports.modelProfiles

    @Provides
    fun harnessLibrary(imports: FoundationAgentRuntimeImports): Machine<HarnessState, HarnessIntent, HarnessOutput> =
        imports.harnessLibrary

    @Provides
    fun schedulerBus(imports: FoundationAgentRuntimeImports): SchedulerBus = imports.schedulerBus

    @Provides
    fun helperAgents(imports: FoundationAgentRuntimeImports): HelperAgents = imports.helperAgents

    @Provides
    @ElementsIntoSet
    fun helperPromptOwners(imports: FoundationAgentRuntimeImports): Set<ScheduledHelperPromptOwner> =
        imports.helperPromptOwners.toSet()

    @Provides
    @ElementsIntoSet
    fun requestOriginObservers(imports: FoundationAgentRuntimeImports): Set<ScheduledRequestOriginObserver> =
        imports.requestOriginObservers.toSet()

    @Provides
    @ElementsIntoSet
    fun serviceUiProviders(imports: FoundationAgentRuntimeImports): Set<ServiceUiProvider> = imports.serviceUiProviders

    @Provides
    fun harnessRuns(
        imports: FoundationAgentRuntimeImports,
    ): Machine<HarnessRunsState, HarnessRunsIntent, HarnessRunsOutput> = imports.harnessRuns

    @Provides
    fun processDecisions(
        imports: FoundationAgentRuntimeImports,
    ): Machine<ProcessDecisionsState, ProcessDecisionsIntent, ProcessDecisionsOutput> = imports.processDecisions
}
