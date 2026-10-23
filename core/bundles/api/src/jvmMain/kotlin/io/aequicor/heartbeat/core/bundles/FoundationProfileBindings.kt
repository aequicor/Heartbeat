package io.aequicor.heartbeat.core.bundles

import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesView
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
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
}

/**
 * Sets of the agent tool catalog that the foundation graph assembles, for the one bundle that owns those sets (the
 * future `engines`). They are a separate interface because a bundle that receives a set accessor through `@Includes`
 * gets a second binding for the same multibinding: its own contributions are replaced by the foundation's merged set,
 * which in turn reads the bundle's export, and the host reports a cross-bundle cycle. Other bundles (`web`) must not
 * include it.
 */
public interface FoundationProfileToolBindings {
    /**
     * Agent tools the foundation graph assembles: the contributions of the features that stay in it plus the parts
     * that other bundles export to it.
     */
    public val agentTools: Set<AgentToolContribution>

    /** Native action analyzers contributed by the features of the foundation. */
    public val nativeActionAnalyzers: Set<NativeActionAnalysis>

    /** Tool output offloads contributed by the features of the foundation. */
    public val toolOutputOffloads: Set<ToolOutputOffload>
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
