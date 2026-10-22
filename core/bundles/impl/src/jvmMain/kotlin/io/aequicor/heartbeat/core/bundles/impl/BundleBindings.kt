package io.aequicor.heartbeat.core.bundles.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleRegistry
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import io.aequicor.heartbeat.core.profilefacade.ProfileGraphAttachment
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import java.util.ServiceLoader

/** Discovers the bundles on the class path (`META-INF/services/…BundleEntry`); none is a valid, empty set. */
internal fun discoverBundles(): List<BundleEntry> =
    ServiceLoader.load(BundleEntry::class.java, BundleEntry::class.java.classLoader).toList()

/** The one [BundleHost] of the application; started by the graph owner (`ensureStarted`). */
@ContributesTo(AppScope::class)
@BindingContainer
public object BundleBindings {
    /** Host over the discovered bundles. */
    @Provides
    @SingleIn(AppScope::class)
    public fun bundleHost(): BundleHost = BundleHost(discoverBundles())

    /** Read-only registry view of the host. */
    @Provides
    public fun bundleRegistry(host: BundleHost): BundleRegistry = host
}

/** Creates the per-profile graphs of the bundles when a profile graph is created. */
@ContributesIntoSet(AppScope::class)
@Inject
internal class BundleProfileAttachment(private val host: BundleHost) : ProfileGraphAttachment {
    override fun attach(id: ProfileId, graph: ProfileGraph, scope: ScopeHandle) {
        host.openProfile(graph, scope)
    }
}
