package io.aequicor.heartbeat.bundles.agentruntime

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileRuntimeBindings
import io.aequicor.heartbeat.core.profilefacade.ProfileStartupAccess
import kotlin.reflect.KClass

/** Entry of the `agent-runtime` bundle, discovered through `ServiceLoader`. */
public class AgentRuntimeBundle : BundleEntry {
    override val id: BundleId = BundleId("agent-runtime")

    override val importsType: KClass<*>? = null

    override val exportTypes: List<KClass<*>> = listOf(AgentRuntimeAppExports::class)

    override fun createGraph(foundation: Any, imports: Any): Any =
        createGraphFactory<AgentRuntimeAppGraph.Factory>().create(foundation as FoundationAppBindings)

    override val hasProfileGraph: Boolean = true

    override val profileImportsType: KClass<*> = AgentRuntimeProfileImports::class

    override val profileExportTypes: List<KClass<*>> =
        listOf(AgentRuntimeProfileExports::class, ProfileStartupAccess::class)

    override fun createProfileGraph(appGraph: Any, foundation: Any, imports: Any): Any =
        (appGraph as AgentRuntimeAppGraph).profileGraphs.create(
            foundation as FoundationProfileBindings,
            imports as AgentRuntimeProfileImports,
            imports,
            foundation as FoundationProfileRuntimeBindings,
        )
}
