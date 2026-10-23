@file:Suppress("FeatureLayerDependency") // hands the foundation's engine registrations to the engines bundle

package io.aequicor.heartbeat.core.bundles.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleRegistry
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.bundles.FoundationEngineSets
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileEngineBindings
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggleControl
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.network.release.ReleaseSource
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import io.aequicor.heartbeat.core.profilefacade.ProfileGraphAttachment
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.core.secrets.SecretStorageInfo
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
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
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.ktor.client.HttpClient
import java.util.ServiceLoader

/** Discovers the bundles on the class path (`META-INF/services/…BundleEntry`); none is a valid, empty set. */
internal fun discoverBundles(): List<BundleEntry> =
    ServiceLoader.load(BundleEntry::class.java, BundleEntry::class.java.classLoader).toList()

/** The one [BundleHost] of the application; it starts the bundles on the first import or `ensureStarted`. */
@ContributesTo(AppScope::class)
@BindingContainer
public object BundleBindings {
    /** Host over the discovered bundles. */
    @Provides
    @SingleIn(AppScope::class)
    public fun bundleHost(foundation: Provider<FoundationAppBindings>): BundleHost =
        BundleHost(discoverBundles()) { foundation() }

    /** Read-only registry view of the host. */
    @Provides
    public fun bundleRegistry(host: BundleHost): BundleRegistry = host
}

/** Creates the per-profile graphs of the bundles when a profile graph is created. */
@ContributesIntoSet(AppScope::class)
@Inject
internal class BundleProfileAttachment(private val host: BundleHost) : ProfileGraphAttachment {
    override fun attach(id: ProfileId, graph: ProfileGraph, scope: ScopeHandle) {
        host.openProfile((graph as FoundationProfileAccess).bundleBindings, scope)
    }
}

/** Accessor of the profile graph: what the bundles' profile graphs receive as their foundation. */
@ContributesTo(ProfileScope::class)
public interface FoundationProfileAccess {
    /** Profile-level bindings of the foundation for bundle graphs. */
    public val bundleBindings: FoundationProfileBindings
}

/** Stateful foundation services of one profile handed to the profile graphs of bundles. */
@ContributesBinding(ProfileScope::class, binding = binding<FoundationProfileBindings>())
@ContributesBinding(ProfileScope::class, binding = binding<FoundationProfileEngineBindings>())
@Inject
internal class DefaultFoundationProfileBindings(
    private val id: Provider<ProfileId>,
    @ForScope(ProfileScope::class) private val scope: Provider<ScopeHandle>,
    @ForScope(ProfileScope::class) private val stores: Provider<DataStores>,
    private val studio: Provider<StudioRemoteScreens>,
    private val facade: Provider<EngineFacade>,
    private val search: Provider<SearchEngine>,
    private val secretStore: Provider<SecretStore>,
    private val models: Provider<ModelProfilesView>,
    private val attachments: Provider<AttachmentsCatalog>,
    private val resolver: Provider<ResourceResolver>,
    private val workspaces: Provider<LocalWorkspaces>,
    private val tools: Provider<Set<AgentToolContribution>>,
    private val analyzers: Provider<Set<NativeActionAnalysis>>,
    private val offloads: Provider<Set<ToolOutputOffload>>,
    private val registrations: Provider<Set<EngineRegistration>>,
    private val hooks: Provider<Set<SessionHook>>,
    private val toolPolicies: Provider<Set<AgentToolPolicyProvider>>,
    private val contextPolicies: Provider<Set<ContextPolicyProvider>>,
    private val virtualWorkspaces: Provider<Set<VirtualWorkspaceProvider>>,
) : FoundationProfileBindings,
    FoundationProfileEngineBindings {
    override val profileId: ProfileId get() = id()
    override val profileScope: ScopeHandle get() = scope()
    override val dataStores: DataStores get() = stores()
    override val studioRemoteScreens: StudioRemoteScreens get() = studio()
    override val secrets: SecretStore get() = secretStore()
    override val modelProfiles: ModelProfilesView get() = models()
    override val engineFacade: EngineFacade get() = facade()
    override val searchEngine: SearchEngine get() = search()
    override val attachmentsCatalog: AttachmentsCatalog get() = attachments()
    override val resourceResolver: ResourceResolver get() = resolver()
    override val localWorkspaces: LocalWorkspaces get() = workspaces()
    override val engineSets: FoundationEngineSets = object : FoundationEngineSets {
        override val resourceResolver: ResourceResolver get() = resolver()
        override val engineRegistrations: Set<EngineRegistration> get() = registrations()
        override val agentTools: Set<AgentToolContribution> get() = tools()
        override val nativeActionAnalyzers: Set<NativeActionAnalysis> get() = analyzers()
        override val toolOutputOffloads: Set<ToolOutputOffload> get() = offloads()
        override val sessionHooks: Set<SessionHook> get() = hooks()
        override val toolPolicyProviders: Set<AgentToolPolicyProvider> get() = toolPolicies()
        override val contextPolicyProviders: Set<ContextPolicyProvider> get() = contextPolicies()
        override val virtualWorkspaceProviders: Set<VirtualWorkspaceProvider> get() = virtualWorkspaces()
    }
}

/**
 * Stateful foundation services handed to bundle graphs. Each is read lazily: the toggles themselves are built from
 * the bundles' toggle sets, so reading them while the bundles are created would be a cycle.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DefaultFoundationBindings(
    private val toggles: Provider<FeatureToggles>,
    private val control: Provider<FeatureToggleControl>,
    private val sessions: Provider<ProfileSessions>,
    private val releases: Provider<ReleaseSource>,
    private val artifacts: Provider<VerifiedArtifactCache>,
    @ForScope(AppScope::class) private val scope: Provider<ScopeHandle>,
    @ForScope(AppScope::class) private val stores: Provider<DataStores>,
    private val root: Provider<StorageRoot>,
    private val downloader: Provider<VerifiedReleaseDownloader>,
    private val scopes: Provider<ScopeFactory>,
    private val hosts: Provider<NavHostFactory>,
    private val launcher: Provider<MachineLauncher>,
    private val registry: Provider<MachineRegistry>,
    private val client: Provider<HttpClient>,
    private val secrets: Provider<SecretStorageInfo>,
) : FoundationAppBindings {
    override val secretStorage: SecretStorageInfo get() = secrets()
    override val featureToggles: FeatureToggles get() = toggles()
    override val featureToggleControl: FeatureToggleControl get() = control()
    override val profileSessions: ProfileSessions get() = sessions()
    override val releaseSource: ReleaseSource get() = releases()
    override val verifiedArtifacts: VerifiedArtifactCache get() = artifacts()
    override val appScope: ScopeHandle get() = scope()
    override val dataStores: DataStores get() = stores()
    override val storageRoot: StorageRoot get() = root()
    override val releaseDownloader: VerifiedReleaseDownloader get() = downloader()
    override val scopeFactory: ScopeFactory get() = scopes()
    override val navHostFactory: NavHostFactory get() = hosts()
    override val machineLauncher: MachineLauncher get() = launcher()
    override val machineRegistry: MachineRegistry get() = registry()
    override val httpClient: HttpClient get() = client()
}
