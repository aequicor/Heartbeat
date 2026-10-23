package io.aequicor.heartbeat.bundles.engines

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileAgentBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileToolBindings
import io.aequicor.heartbeat.core.profilefacade.ProfileStartupAccess
import kotlin.reflect.KClass

/** Entry of the `engines` bundle, discovered through `ServiceLoader`. */
public class EnginesBundle : BundleEntry {
    override val id: BundleId = BundleId("engines")

    override val importsType: KClass<*>? = null

    override val exportTypes: List<KClass<*>> = listOf(EnginesAppExports::class)

    override fun createGraph(foundation: Any, imports: Any): Any =
        createGraphFactory<EnginesAppGraph.Factory>().create(foundation as FoundationAppBindings)

    override val hasProfileGraph: Boolean = true

    override val profileImportsType: KClass<*> = EnginesProfileImports::class

    override val profileExportTypes: List<KClass<*>> = listOf(EnginesProfileExports::class, ProfileStartupAccess::class)

    override fun createProfileGraph(appGraph: Any, foundation: Any, imports: Any): Any =
        (appGraph as EnginesAppGraph).profileGraphs.create(
            foundation as FoundationProfileBindings,
            foundation as FoundationProfileAgentBindings,
            foundation as FoundationProfileToolBindings,
            imports as EnginesProfileImports,
        )
}
