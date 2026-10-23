package io.aequicor.heartbeat.bundles.engines

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.datastore.ProfileStorageCleaner
import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph

/** What the `engines` bundle publishes to the other bundles and to the foundation at the application level. */
public interface EnginesAppExports {
    /** Toggles of the bundle's features; the foundation merges them into the application's set. */
    public val featureToggles: Set<FeatureToggle<*>>

    /** Runtime pool and engine processes to finish before the process exits; merged into the foundation's set. */
    public val shutdownParticipants: Set<AppShutdownParticipant>

    /** Wipes the engine data of a removed profile; the foundation merges it into its own set. */
    public val profileStorageCleaners: Set<ProfileStorageCleaner>
}

/**
 * App-level graph of the `engines` bundle: the contributions of its member features, compiled statically. The profile
 * graph of the foundation (`ProfileGraph`) is not part of it: the bundle has [EnginesProfileGraph] of its own.
 */
@DependencyGraph(AppScope::class, excludes = [ProfileGraph.Factory::class])
internal interface EnginesAppGraph : EnginesAppExports {
    /** Creates the profile graph of the bundle for one profile. */
    val profileGraphs: EnginesProfileGraph.Factory

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationAppBindings,
        ): EnginesAppGraph
    }
}
