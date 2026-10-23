package io.aequicor.heartbeat.bundles.studio

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.di.ProfileScope

/**
 * What the profile graph of the `studio` bundle publishes to the foundation of the same profile. Its profile startups
 * go out through `ProfileStartupAccess`, which the graph implements as a contribution of the profile scope.
 */
public interface StudioProfileExports

/** Profile-level graph of the `studio` bundle: the profile contributions of its member features. */
@GraphExtension(ProfileScope::class)
internal interface StudioProfileGraph : StudioProfileExports {
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationProfileBindings,
        ): StudioProfileGraph
    }
}
