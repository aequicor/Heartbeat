package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.bundles.impl.BundleHost
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import io.aequicor.heartbeat.core.secrets.SecretStorageInfo
import io.aequicor.heartbeat.core.secrets.impl.SecretsConfig

/** Access to the host of the runtime-loaded desktop bundles: `(graph as BundleHostAccess).bundleHost`. */
internal interface BundleHostAccess {
    /** Runtime-loaded desktop bundles; none until a `:bundles:*` assembler is on the class path. */
    val bundleHost: BundleHost
}

@DependencyGraph(AppScope::class)
internal interface JvmHeartbeatGraph :
    HeartbeatGraph,
    BundleHostAccess {
    val secretStorage: SecretStorageInfo

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides secretsConfig: SecretsConfig,
        ): JvmHeartbeatGraph
    }
}

/** Health of every desktop bundle after the application graph started them (the launcher reads it to roll back). */
public fun HeartbeatGraph.bundleHealth(): Map<BundleId, BundleHealth> = (this as BundleHostAccess).bundleHost.health

/** Health of the profile graphs of the bundles of the profile [graph] belongs to; empty while none is open. */
public fun HeartbeatGraph.profileBundleHealth(profile: ProfileGraph): Map<BundleId, BundleHealth> =
    (this as BundleHostAccess).bundleHost.profileHealth(profile.scope).orEmpty()

/**
 * Creates the application graph. Only the development and testing desktop entry points opt into local credential
 * storage ([hasLocalSecrets]); production keeps the system protection.
 */
fun createHeartbeatGraph(
    isDevelopment: Boolean = false,
    hasLocalSecrets: Boolean = isDevelopment,
    allowSecretInteraction: Boolean = true,
): HeartbeatGraph = createGraphFactory<JvmHeartbeatGraph.Factory>().create(
    SecretsConfig(isDevelopment = hasLocalSecrets, isInteractionAllowed = allowSecretInteraction),
).also { graph -> graph.bundleHost.ensureStarted() }
