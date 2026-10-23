package io.aequicor.heartbeat.bundles.studio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph

/** What the `studio` bundle publishes to the other bundles and to the foundation at the application level. */
public interface StudioAppExports {
    /** Toggles of the bundle's features; the foundation merges them into the application's set. */
    public val featureToggles: Set<FeatureToggle<*>>
}

/**
 * App-level graph of the `studio` bundle: the contributions of its member features, compiled statically. The profile
 * graph of the foundation (`ProfileGraph`) is not part of it: the bundle has [StudioProfileGraph] of its own.
 */
@DependencyGraph(AppScope::class, excludes = [ProfileGraph.Factory::class])
internal interface StudioAppGraph : StudioAppExports {
    /** Creates the profile graph of the bundle for one profile. */
    val profileGraphs: StudioProfileGraph.Factory

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationAppBindings,
        ): StudioAppGraph
    }
}
