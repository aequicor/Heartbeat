@file:Suppress("FeatureLayerDependency") // hands the foundation's engine registrations to the engines bundle

package io.aequicor.heartbeat.core.bundles

import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesView
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspaceProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoverySource
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledEventOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.SchedulerEventSource
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine

/**
 * Stateful profile-level bindings the foundation hands to the profile graph of a bundle (`@Includes`). Read lazily:
 * a bundle graph is created while the foundation profile graph is still being attached. Like the app-level
 * [FoundationAppBindings], it lists only what bundles need; a bundle that needs more asks for it here.
 */
public interface FoundationProfileBindings {
    /** Identifier of the profile the graph belongs to. */
    public val profileId: ProfileId

    /** Scope of the profile: coroutines and cleanup of services that live as long as the profile. */
    @ForScope(ProfileScope::class)
    public val profileScope: ScopeHandle

    /** Stores of the profile's own data. */
    @ForScope(ProfileScope::class)
    public val dataStores: DataStores

    /** Studio screens that the Chromium side panel opens and drives (the studio stays in the foundation for now). */
    public val studioRemoteScreens: StudioRemoteScreens

    /** Credentials of the profile. */
    public val secrets: SecretStore

    /** Library of model descriptions; adaptive subagents stay in the foundation for now. */
    public val modelProfiles: ModelProfilesView

    /** Catalog of the profile's attachments (stays in the foundation for now). */
    public val attachmentsCatalog: AttachmentsCatalog

    /**
     * Parts of the sets that the `engines` bundle owns. They sit behind one holder instead of plain `Set` accessors:
     * a graph that includes this interface would otherwise bind each `Set` twice, as an accessor and as a multibinding,
     * and the accessor replaces the graph's own contributions with the foundation's merged set.
     */
    public val engineSets: FoundationEngineSets

    /** Parts of the sets that the `agent-runtime` bundle owns (see [FoundationAgentRuntimeSets]). */
    public val agentRuntimeSets: FoundationAgentRuntimeSets
}

/**
 * What the features that stay in the foundation contribute to the sets the `agent-runtime` bundle owns. Behind a
 * holder for the same reason as [FoundationEngineSets]: a `Set` accessor of an included interface would shadow the
 * bundle's own multibinding.
 */
public interface FoundationAgentRuntimeSets {
    /** Sections of the project configuration tree contributed by the features of the foundation. */
    public val configSections: Set<ConfigSection>

    /** Hosts of the foundation (the studio) that own sessions and deliver wake prompts through their turns. */
    public val scheduledSessionHosts: Set<ScheduledSessionHost>

    /** Admission controllers of the foundation's features (checklists) for the wakes they own. */
    public val scheduledWakeOwners: Set<ScheduledWakeOwner>

    /** Publisher controllers of the foundation's features for exact delivery of their events. */
    public val scheduledEventOwners: Set<ScheduledEventOwner>

    /** Durable helper slots of the foundation's features, read before any new background admission. */
    public val helperCapacityRecoverySources: Set<HelperCapacityRecoverySource>

    /** Platform signal sources contributed by the foundation. */
    public val schedulerEventSources: Set<SchedulerEventSource>
}

/**
 * What the features that stay in the foundation contribute to the sets the facade of the `engines` bundle assembles.
 * A bundle that owns a set merges these parts with its own contributions and with the parts other bundles export.
 */
public interface FoundationEngineSets {
    /** Resolver of the profile's attachments: the foundation keeps them, the facade reads them through it. */
    public val resourceResolver: ResourceResolver

    /** Engine registrations contributed by the foundation (tests and hosts that bring their own adapter). */
    public val engineRegistrations: Set<EngineRegistration>

    /** Agent tools of the features that stay in the foundation. */
    public val agentTools: Set<AgentToolContribution>

    /** Native action analyzers contributed by the features of the foundation. */
    public val nativeActionAnalyzers: Set<NativeActionAnalysis>

    /** Tool output offloads contributed by the features of the foundation. */
    public val toolOutputOffloads: Set<ToolOutputOffload>

    /** Session hooks contributed by the features of the foundation (the facade of `engines` runs them). */
    public val sessionHooks: Set<SessionHook>

    /** Tool policy providers contributed by the features of the foundation. */
    public val toolPolicyProviders: Set<AgentToolPolicyProvider>

    /** Context policy providers contributed by the features of the foundation. */
    public val contextPolicyProviders: Set<ContextPolicyProvider>

    /** Virtual workspace providers contributed by the features of the foundation. */
    public val virtualWorkspaceProviders: Set<VirtualWorkspaceProvider>
}

/**
 * Services of the AI engines the foundation hands to the profile graphs of the bundles that only use them (`web`).
 * They are a separate interface because the `engines` bundle owns the facade and the search: its own profile graph must
 * not receive them from the foundation, or each would have two bindings. The foundation backs them with the lazy
 * imports from `engines` (the resource resolver is the foundation's own, over its attachments).
 */
public interface FoundationProfileEngineBindings {
    /** Sessions and models of the AI engines. */
    public val engineFacade: EngineFacade

    /** Web search that research runs use. */
    public val searchEngine: SearchEngine

    /** Reads the bytes of attachments and other prompt resources of the profile. */
    public val resourceResolver: ResourceResolver

    /** Local projects of the profile (worktrees and agent learning work on them). */
    public val localWorkspaces: LocalWorkspaces
}
