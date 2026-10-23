@file:Suppress("FeatureLayerDependency") // the bundle is the application side of the engine SPI

package io.aequicor.heartbeat.bundles.engines

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspaceProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration

/**
 * What the profile graph of `engines` takes from other bundles; implemented by the host with lazy proxies. Sets of this
 * interface are owned by `engines` (its facade assembles them), so the bundle never exports them.
 */
public interface EnginesProfileImports {
    /** Agent tools of the bundles: the browser (`web`), worktrees and learning (`agent-runtime`). */
    public val agentTools: Set<AgentToolContribution>

    /** Virtual workspaces of the `agent-runtime` bundle (sandboxes of worktree tasks). */
    public val virtualWorkspaceProviders: Set<VirtualWorkspaceProvider>

    /**
     * Session hooks of the `agent-runtime` bundle (harnesses watch and feed sessions through them) and of the `studio`
     * bundle (context ledger and handoff).
     */
    public val sessionHooks: Set<SessionHook>
}

/** The foundation owns the attachments; the facade of `engines` resolves prompt resources through its resolver. */
@ContributesBinding(ProfileScope::class, priority = 1)
@Inject
internal class FoundationResourceResolver(private val foundation: FoundationProfileBindings) : ResourceResolver {
    override suspend fun resolve(reference: ResourceRef): ResolvedResource? =
        foundation.engineSets.resourceResolver.resolve(reference)
}

/**
 * Parts of the sets that `engines` owns and other participants export to it: the foundation through
 * [FoundationProfileBindings], the `web` and `agent-runtime` bundles through [EnginesProfileImports]. Every element is
 * read when the set is requested, which happens after the profile graphs of the bundles are open.
 */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object EnginesFoundationSets {
    @Provides
    @ElementsIntoSet
    fun foundationAgentTools(foundation: FoundationProfileBindings): Set<AgentToolContribution> =
        foundation.engineSets.agentTools

    @Provides
    @ElementsIntoSet
    fun bundleAgentTools(imports: EnginesProfileImports): Set<AgentToolContribution> = imports.agentTools

    @Provides
    @ElementsIntoSet
    fun bundleVirtualWorkspaces(imports: EnginesProfileImports): Set<VirtualWorkspaceProvider> =
        imports.virtualWorkspaceProviders

    @Provides
    @ElementsIntoSet
    fun bundleSessionHooks(imports: EnginesProfileImports): Set<SessionHook> = imports.sessionHooks

    @Provides
    @ElementsIntoSet
    fun foundationRegistrations(foundation: FoundationProfileBindings): Set<EngineRegistration> =
        foundation.engineSets.engineRegistrations

    @Provides
    @ElementsIntoSet
    fun foundationAnalyzers(foundation: FoundationProfileBindings): Set<NativeActionAnalysis> =
        foundation.engineSets.nativeActionAnalyzers

    @Provides
    @ElementsIntoSet
    fun foundationOffloads(foundation: FoundationProfileBindings): Set<ToolOutputOffload> =
        foundation.engineSets.toolOutputOffloads

    @Provides
    @ElementsIntoSet
    fun foundationHooks(foundation: FoundationProfileBindings): Set<SessionHook> = foundation.engineSets.sessionHooks

    @Provides
    @ElementsIntoSet
    fun foundationToolPolicies(foundation: FoundationProfileBindings): Set<AgentToolPolicyProvider> =
        foundation.engineSets.toolPolicyProviders

    @Provides
    @ElementsIntoSet
    fun foundationContextPolicies(foundation: FoundationProfileBindings): Set<ContextPolicyProvider> =
        foundation.engineSets.contextPolicyProviders

    @Provides
    @ElementsIntoSet
    fun foundationVirtualWorkspaces(foundation: FoundationProfileBindings): Set<VirtualWorkspaceProvider> =
        foundation.engineSets.virtualWorkspaceProviders
}
