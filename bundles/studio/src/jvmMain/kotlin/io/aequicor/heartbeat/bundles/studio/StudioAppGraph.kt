package io.aequicor.heartbeat.bundles.studio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.datastore.ProfileStorageCleaner
import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapturePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermissionGuide

/** What the `studio` bundle publishes to the other bundles and to the foundation at the application level. */
public interface StudioAppExports {
    /** Toggles of the bundle's features; the foundation merges them into the application's set. */
    public val featureToggles: Set<FeatureToggle<*>>

    /** Native runtimes to finish before the process exits; merged into the foundation's set. */
    public val shutdownParticipants: Set<AppShutdownParticipant>

    /** Wipes the studio data of a removed profile; the foundation merges it into its own set. */
    public val profileStorageCleaners: Set<ProfileStorageCleaner>

    /** Coordinates exclusion of native session windows from computer-use screenshots and input. */
    public val capturePresentation: ComputerUseCapturePresentation

    /** The macOS permission grant the desktop host guides with a draggable application tile. */
    public val permissionGuide: ComputerUsePermissionGuide
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
