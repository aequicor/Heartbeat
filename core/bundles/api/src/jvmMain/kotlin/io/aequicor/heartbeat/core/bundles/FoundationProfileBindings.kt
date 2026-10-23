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
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspaceProvider
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
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

    /** Credentials of the profile. */
    public val secrets: SecretStore

    /**
     * Reads attachments and other prompt resources of the profile. It is a [HostResourceResolver] and not a
     * `ResourceResolver`: a bundle that compiles the facade has its own `ResourceResolver` binding, and a second
     * binding of the same type would clash.
     */
    public val hostResources: HostResourceResolver
}

/** Resolution of the host's prompt resources (attachments), handed to the bundle that owns the engine facade. */
public fun interface HostResourceResolver {
    /** Reads the resource, or `null` for a namespace the foundation does not own. */
    public suspend fun resolve(reference: ResourceRef): ResolvedResource?
}

/**
 * The parts of the agent runtime sets that the features staying in the foundation contribute, for the one bundle that
 * owns those sets (`engines`: its facade collects them). Only what the foundation compiles itself: never the parts of
 * other bundles, because those reach the owner directly and a merged set read back from the owner is a cycle.
 *
 * The owner takes it as a `@Provides` instance, never as `@Includes`: included, its `Set<T>` accessors would become a
 * second binding of the graph's own multibinding and replace the owner's contributions. The owner adds every set with
 * `@ElementsIntoSet`. Other bundles (`web`) must not take it.
 */
public interface FoundationProfileToolBindings {
    /** Agent tools of the features that stay in the foundation. */
    public val agentTools: Set<AgentToolContribution>

    /** Native action analyzers contributed by the features of the foundation. */
    public val nativeActionAnalyzers: Set<NativeActionAnalysis>

    /** Tool output offloads contributed by the features of the foundation. */
    public val toolOutputOffloads: Set<ToolOutputOffload>

    /** Session hooks contributed by the features of the foundation. */
    public val sessionHooks: Set<SessionHook>

    /** Tool policy providers contributed by the features of the foundation. */
    public val toolPolicyProviders: Set<AgentToolPolicyProvider>

    /** Context policy providers contributed by the features of the foundation. */
    public val contextPolicyProviders: Set<ContextPolicyProvider>

    /** Virtual workspace providers contributed by the features of the foundation. */
    public val virtualWorkspaceProviders: Set<VirtualWorkspaceProvider>
}

/**
 * Services of the studio the foundation hands to the profile graphs of the bundles that only use them (`web`). They are
 * a separate interface because the `studio` bundle owns these services: its own profile graph must not receive them
 * from the foundation, or each would have two bindings. The foundation backs them with the lazy imports from `studio`.
 */
public interface FoundationProfileStudioBindings {
    /** Studio screens that the Chromium side panel opens and drives. */
    public val studioRemoteScreens: StudioRemoteScreens

    /** Catalog of the profile's attachments. */
    public val attachmentsCatalog: AttachmentsCatalog
}

/**
 * Services of the agent runtime the foundation hands to the profile graphs of the bundles that only use them (`web`,
 * `engines`). They are a separate interface because the `agent-runtime` bundle owns these services: its own profile
 * graph must not receive them from the foundation, or each would have two bindings. The foundation backs them with the
 * lazy imports from `agent-runtime`.
 */
public interface FoundationProfileAgentBindings {
    /** Library of model descriptions of the adaptive subagents. */
    public val modelProfiles: ModelProfilesView
}

/**
 * The parts of the sets owned by the `agent-runtime` bundle that the features staying in the foundation contribute
 * (the studio hosts sessions for the scheduler, the checklist owns wake-ups, service-as-ui brings a config section).
 * The owner takes it as a `@Provides` instance, like [FoundationProfileToolBindings], and adds every set with
 * `@ElementsIntoSet`. Only what the foundation compiles itself.
 */
public interface FoundationProfileRuntimeBindings {
    /** Hosts of scheduled sessions contributed by the foundation. */
    public val scheduledSessionHosts: Set<ScheduledSessionHost>

    /** Owners of scheduled wake-ups contributed by the foundation. */
    public val scheduledWakeOwners: Set<ScheduledWakeOwner>

    /** Sections of the project configuration contributed by the foundation. */
    public val configSections: Set<ConfigSection>
}

/**
 * Services of the AI engines the foundation hands to the profile graphs of the bundles that only use them (`web`).
 * They are a separate interface because the `engines` bundle owns these services: its own profile graph must not
 * receive them from the foundation, or each would have two bindings. The foundation backs them with the lazy
 * imports from `engines`.
 */
public interface FoundationProfileEngineBindings {
    /** Sessions and models of the AI engines. */
    public val engineFacade: EngineFacade

    /** Web search that research runs use. */
    public val searchEngine: SearchEngine

    /** Reads the bytes of attachments and other prompt resources of the profile. */
    public val resourceResolver: ResourceResolver
}
