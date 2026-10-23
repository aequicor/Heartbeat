package io.aequicor.heartbeat.bundles.engines

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.RouteEntry

/**
 * What the profile graph of the `engines` bundle publishes to the foundation of the same profile. Its profile
 * startups go out through `ProfileStartupAccess`, which the graph implements as a contribution of the profile scope.
 */
public interface EnginesProfileExports {
    /** Navigation entries of the engine screens; the foundation merges them into the profile routes. */
    @ForScope(ProfileScope::class)
    public val profileRoutes: Set<RouteEntry<*>>
}

/** Profile-level graph of the `engines` bundle: the profile contributions of its member features. */
@GraphExtension(ProfileScope::class)
internal interface EnginesProfileGraph : EnginesProfileExports {
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationProfileBindings,
        ): EnginesProfileGraph
    }
}
