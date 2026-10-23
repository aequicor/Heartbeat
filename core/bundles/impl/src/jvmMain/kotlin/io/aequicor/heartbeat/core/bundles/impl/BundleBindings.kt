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
import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleRegistry
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import io.aequicor.heartbeat.core.profilefacade.ProfileGraphAttachment
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
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
@ContributesBinding(ProfileScope::class)
@Inject
internal class DefaultFoundationProfileBindings(
    private val id: Provider<ProfileId>,
    @ForScope(ProfileScope::class) private val scope: Provider<ScopeHandle>,
    @ForScope(ProfileScope::class) private val stores: Provider<DataStores>,
    private val studio: Provider<StudioRemoteScreens>,
) : FoundationProfileBindings {
    override val profileId: ProfileId get() = id()
    override val profileScope: ScopeHandle get() = scope()
    override val dataStores: DataStores get() = stores()
    override val studioRemoteScreens: StudioRemoteScreens get() = studio()
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
    private val artifacts: Provider<VerifiedArtifactCache>,
    @ForScope(AppScope::class) private val scope: Provider<ScopeHandle>,
    @ForScope(AppScope::class) private val stores: Provider<DataStores>,
    private val root: Provider<StorageRoot>,
    private val downloader: Provider<VerifiedReleaseDownloader>,
    private val scopes: Provider<ScopeFactory>,
    private val launcher: Provider<MachineLauncher>,
    private val registry: Provider<MachineRegistry>,
    private val client: Provider<HttpClient>,
) : FoundationAppBindings {
    override val featureToggles: FeatureToggles get() = toggles()
    override val verifiedArtifacts: VerifiedArtifactCache get() = artifacts()
    override val appScope: ScopeHandle get() = scope()
    override val dataStores: DataStores get() = stores()
    override val storageRoot: StorageRoot get() = root()
    override val releaseDownloader: VerifiedReleaseDownloader get() = downloader()
    override val scopeFactory: ScopeFactory get() = scopes()
    override val machineLauncher: MachineLauncher get() = launcher()
    override val machineRegistry: MachineRegistry get() = registry()
    override val httpClient: HttpClient get() = client()
}
