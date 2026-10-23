package io.aequicor.heartbeat.bundles.studio

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileAgentBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.profilefacade.ProfileStartupAccess
import kotlin.reflect.KClass

/** Entry of the `studio` bundle, discovered through `ServiceLoader`. */
public class StudioBundle : BundleEntry {
    override val id: BundleId = BundleId("studio")

    override val importsType: KClass<*>? = null

    override val exportTypes: List<KClass<*>> = listOf(StudioAppExports::class)

    override fun createGraph(foundation: Any, imports: Any): Any =
        createGraphFactory<StudioAppGraph.Factory>().create(foundation as FoundationAppBindings)

    override val hasProfileGraph: Boolean = true

    override val profileImportsType: KClass<*> = StudioProfileImports::class

    override val profileExportTypes: List<KClass<*>> = listOf(StudioProfileExports::class, ProfileStartupAccess::class)

    override fun createProfileGraph(appGraph: Any, foundation: Any, imports: Any): Any =
        (appGraph as StudioAppGraph).profileGraphs.create(
            foundation as FoundationProfileBindings,
            foundation as FoundationProfileAgentBindings,
            imports as StudioProfileImports,
            imports,
        )
}
