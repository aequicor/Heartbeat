package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.bundles.impl.BundleHost
import io.aequicor.heartbeat.core.logging.desktopDataName
import io.aequicor.heartbeat.core.secrets.SecretStorageInfo
import io.aequicor.heartbeat.core.secrets.impl.SecretsConfig

@DependencyGraph(AppScope::class)
internal interface JvmHeartbeatGraph : HeartbeatGraph {
    val secretStorage: SecretStorageInfo

    /** Runtime-loaded desktop bundles; none until a `:bundles:*` assembler is on the class path. */
    val bundleHost: BundleHost

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides secretsConfig: SecretsConfig,
        ): JvmHeartbeatGraph
    }
}

/**
 * Creates the application graph. Only the development and testing desktop entry points opt into local credential
 * storage ([hasLocalSecrets]); production keeps the system protection.
 */
fun createHeartbeatGraph(
    isDevelopment: Boolean = false,
    hasLocalSecrets: Boolean = isDevelopment,
    allowSecretInteraction: Boolean = true,
): HeartbeatGraph = createGraphFactory<JvmHeartbeatGraph.Factory>().create(
    SecretsConfig(
        service = secretsService(),
        isDevelopment = hasLocalSecrets,
        isInteractionAllowed = allowSecretInteraction,
    ),
).also { graph -> graph.bundleHost.ensureStarted() }

/**
 * The Keychain/keyring service of this build: the stable one of Heartbeat, or one of its own for a build with its own
 * data name (the dev channel), so it never reads or rewrites the profiles' secrets of an installed Heartbeat.
 */
internal fun secretsService(dataName: String = desktopDataName()): String = SecretsConfig().service.let { service ->
    if (dataName == "Heartbeat") service else "$service.${dataName.lowercase()}"
}
