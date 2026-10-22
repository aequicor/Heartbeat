package io.aequicor.heartbeat.bundles.web

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRenderer

/** What the `web` bundle publishes to the other bundles and to the foundation. */
public interface WebAppExports {
    /** The local PlantUML renderer; draws only while its toggle is on. */
    public val plantUml: PlantUmlRenderer

    /** Toggles of the bundle's features; the foundation merges them into the application's set. */
    public val featureToggles: Set<FeatureToggle<*>>
}

/** App-level graph of the `web` bundle: the contributions of its member features, compiled statically. */
@DependencyGraph(AppScope::class)
internal interface WebAppGraph : WebAppExports {
    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationAppBindings,
        ): WebAppGraph
    }
}
