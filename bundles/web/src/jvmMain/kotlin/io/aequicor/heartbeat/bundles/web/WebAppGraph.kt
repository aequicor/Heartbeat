package io.aequicor.heartbeat.bundles.web

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.datastore.ProfileStorageCleaner
import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRenderer

/** What the `web` bundle publishes to the other bundles and to the foundation. */
public interface WebAppExports {
    /** The local PlantUML renderer; draws only while its toggle is on. */
    public val plantUml: PlantUmlRenderer

    /** Toggles of the bundle's features; the foundation merges them into the application's set. */
    public val featureToggles: Set<FeatureToggle<*>>

    /** Native browser runtime to finish before the process exits; the foundation merges it into its own set. */
    public val shutdownParticipants: Set<AppShutdownParticipant>

    /** Wipes the browser data of a removed profile; the foundation merges it into its own set. */
    public val profileStorageCleaners: Set<ProfileStorageCleaner>
}

/**
 * App-level graph of the `web` bundle: the contributions of its member features, compiled statically. The profile
 * graph of the foundation (`ProfileGraph`) is not part of it: the bundle has [WebProfileGraph] of its own.
 */
@DependencyGraph(AppScope::class, excludes = [ProfileGraph.Factory::class])
internal interface WebAppGraph : WebAppExports {
    /** Creates the profile graph of the bundle for one profile. */
    val profileGraphs: WebProfileGraph.Factory

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationAppBindings,
        ): WebAppGraph
    }
}
