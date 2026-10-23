package io.aequicor.heartbeat.bundles.shell

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import kotlin.reflect.KClass

/** Entry of the `shell` bundle, discovered through `ServiceLoader`. */
public class ShellBundle : BundleEntry {
    override val id: BundleId = BundleId("shell")

    override val importsType: KClass<*>? = null

    override val exportTypes: List<KClass<*>> = listOf(ShellAppExports::class)

    override fun createGraph(foundation: Any, imports: Any): Any =
        createGraphFactory<ShellAppGraph.Factory>().create(foundation as FoundationAppBindings)
}
