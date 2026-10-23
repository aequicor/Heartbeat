package io.aequicor.heartbeat.bundles.engines

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.FoundationProfileToolBindings
import io.aequicor.heartbeat.core.bundles.HostResourceResolver
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
 * The facade of the bundle owns the sets of the agent runtime. Besides the contributions of the bundle's own features
 * they take the parts of the features that stay in the foundation, and (agent tools) the parts of other bundles.
 * Everything is read when the set is built, never while the graph is created.
 */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object EnginesAgentRuntimeSets {
    /** Tools of the foundation features and of the other bundles. */
    @Provides
    @ElementsIntoSet
    fun agentTools(tools: FoundationProfileToolBindings, imports: EnginesProfileImports): Set<AgentToolContribution> =
        tools.agentTools + imports.agentTools

    /** The complete tool catalog in a form the foundation can read without owning the set. */
    @Provides
    fun agentToolContributions(contributions: Set<AgentToolContribution>): List<AgentToolContribution> =
        contributions.toList()

    /** Engine adapters of the other bundles. */
    @Provides
    @ElementsIntoSet
    fun engineRegistrations(imports: EnginesProfileImports): Set<EngineRegistration> = imports.engineRegistrations

    /** Everything registered, in a form the foundation can import without owning the set. */
    @Provides
    fun registeredEngines(registrations: Set<EngineRegistration>): List<EngineRegistration> = registrations.toList()

    /** Native action analyzers of the foundation features. */
    @Provides
    @ElementsIntoSet
    fun nativeActionAnalyzers(
        tools: FoundationProfileToolBindings,
        imports: EnginesProfileImports,
    ): Set<NativeActionAnalysis> = tools.nativeActionAnalyzers + imports.nativeActionAnalyzers

    /** Tool output offloads of the foundation features and of the other bundles. */
    @Provides
    @ElementsIntoSet
    fun toolOutputOffloads(
        tools: FoundationProfileToolBindings,
        imports: EnginesProfileImports,
    ): Set<ToolOutputOffload> = tools.toolOutputOffloads + imports.toolOutputOffloads

    /** Session hooks of the foundation features and of the other bundles. */
    @Provides
    @ElementsIntoSet
    fun sessionHooks(tools: FoundationProfileToolBindings, imports: EnginesProfileImports): Set<SessionHook> =
        tools.sessionHooks + imports.sessionHooks

    /** Tool policy providers of the foundation features and of the other bundles. */
    @Provides
    @ElementsIntoSet
    fun toolPolicyProviders(
        tools: FoundationProfileToolBindings,
        imports: EnginesProfileImports,
    ): Set<AgentToolPolicyProvider> = tools.toolPolicyProviders + imports.toolPolicyProviders

    /** Context policy providers of the foundation features and of the other bundles. */
    @Provides
    @ElementsIntoSet
    fun contextPolicyProviders(
        tools: FoundationProfileToolBindings,
        imports: EnginesProfileImports,
    ): Set<ContextPolicyProvider> = tools.contextPolicyProviders + imports.contextPolicyProviders

    /** Virtual workspace providers of the foundation features and of the other bundles. */
    @Provides
    @ElementsIntoSet
    fun virtualWorkspaceProviders(
        tools: FoundationProfileToolBindings,
        imports: EnginesProfileImports,
    ): Set<VirtualWorkspaceProvider> = tools.virtualWorkspaceProviders + imports.virtualWorkspaceProviders
}

/**
 * Prompt resources are owned by the attachments of the foundation. The facade's own resolver reads nothing, so this
 * one outranks it (priority 1) and forwards to the foundation.
 */
@ContributesBinding(ProfileScope::class, priority = 1)
@Inject
internal class HostBackedResourceResolver(private val host: HostResourceResolver) : ResourceResolver {
    override suspend fun resolve(reference: ResourceRef): ResolvedResource? = host.resolve(reference)
}
