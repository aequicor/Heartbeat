package io.aequicor.heartbeat.bundles.web

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileAttachmentBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileEngineBindings
import io.aequicor.heartbeat.core.profilefacade.ProfileStartupAccess
import kotlin.reflect.KClass

/** Entry of the `web` bundle, discovered through `ServiceLoader`. */
public class WebBundle : BundleEntry {
    override val id: BundleId = BundleId("web")

    override val importsType: KClass<*>? = null

    override val exportTypes: List<KClass<*>> = listOf(WebAppExports::class)

    override fun createGraph(foundation: Any, imports: Any): Any =
        createGraphFactory<WebAppGraph.Factory>().create(foundation as FoundationAppBindings)

    override val hasProfileGraph: Boolean = true

    override val profileExportTypes: List<KClass<*>> = listOf(WebProfileExports::class, ProfileStartupAccess::class)

    override fun createProfileGraph(appGraph: Any, foundation: Any, imports: Any): Any =
        (appGraph as WebAppGraph).profileGraphs.create(
            foundation as FoundationProfileBindings,
            foundation as FoundationProfileEngineBindings,
            foundation as FoundationProfileAttachmentBindings,
        )
}
