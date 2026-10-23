package io.aequicor.heartbeat.core.bundles

import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
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

    /** Sessions and models of the AI engines (the engine facade stays in the foundation for now). */
    public val engineFacade: EngineFacade

    /** Web search that research runs use (stays in the foundation for now). */
    public val searchEngine: SearchEngine

    /** Catalog of the profile's attachments (stays in the foundation for now). */
    public val attachmentsCatalog: AttachmentsCatalog

    /** Reads the bytes of attachments and other prompt resources of the profile. */
    public val resourceResolver: ResourceResolver

    /**
     * Agent tools the foundation graph assembles: the contributions of the features that stay in it plus the parts
     * that other bundles export to it. A bundle that owns the set merges them with its own contributions.
     */
    public val agentTools: Set<AgentToolContribution>

    /** Native action analyzers contributed by the features of the foundation. */
    public val nativeActionAnalyzers: Set<NativeActionAnalysis>

    /** Tool output offloads contributed by the features of the foundation. */
    public val toolOutputOffloads: Set<ToolOutputOffload>
}
