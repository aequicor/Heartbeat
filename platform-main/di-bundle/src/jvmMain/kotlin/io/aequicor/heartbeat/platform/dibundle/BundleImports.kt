package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.BundleUnavailableException
import io.aequicor.heartbeat.core.bundles.impl.BundleHost
import io.aequicor.heartbeat.core.datastore.ProfileStorageCleaner
import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRenderer
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRequest
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What the foundation takes from the runtime-loaded bundles; implemented by the host with lazy proxies.
 * Every entry is a service of the legacy graph that used to be compiled in, so existing consumers
 * (`PlantUmlAccessors`, the Chromium bridge, the toggles panel) keep working unchanged.
 */
internal interface FoundationBundleImports {
    /** The `web` bundle's local PlantUML renderer. */
    val plantUml: PlantUmlRenderer

    /** Feature toggles contributed by every bundle. */
    val featureToggles: Set<FeatureToggle<*>>

    /** Native runtimes of the bundles to finish before the process exits. */
    val shutdownParticipants: Set<AppShutdownParticipant>

    /** Wipes of the data a bundle keeps for a removed profile. */
    val profileStorageCleaners: Set<ProfileStorageCleaner>
}

/** Renderer of a diagram while the `web` bundle is not running: the feature reports itself as unavailable. */
internal object UnavailablePlantUmlRenderer : PlantUmlRenderer {
    override val availability: Flow<Boolean> = flowOf(false)

    override suspend fun render(request: PlantUmlRequest): PlantUmlResult = PlantUmlResult.Unsupported
}

/** Bridges bundle exports into the foundation graph. */
@ContributesTo(AppScope::class)
@BindingContainer
internal object BundleImportBindings {
    private val log = Log.tag("BundleImports")

    @Provides
    fun plantUml(host: BundleHost): PlantUmlRenderer = try {
        host.foundationImports(FoundationBundleImports::class).plantUml
    } catch (e: BundleUnavailableException) {
        log.w(e) { "PlantUML bundle is unavailable; diagrams stay code" }
        UnavailablePlantUmlRenderer
    }

    @Provides
    @ElementsIntoSet
    fun bundleToggles(host: BundleHost): Set<FeatureToggle<*>> =
        host.foundationImports(FoundationBundleImports::class).featureToggles

    @Provides
    @ElementsIntoSet
    fun bundleShutdown(host: BundleHost): Set<AppShutdownParticipant> =
        host.foundationImports(FoundationBundleImports::class).shutdownParticipants

    @Provides
    @ElementsIntoSet
    fun bundleProfileCleaners(host: BundleHost): Set<ProfileStorageCleaner> =
        host.foundationImports(FoundationBundleImports::class).profileStorageCleaners
}
