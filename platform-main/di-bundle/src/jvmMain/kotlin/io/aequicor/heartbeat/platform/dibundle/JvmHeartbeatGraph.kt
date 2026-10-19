package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.secrets.SecretStorageInfo
import io.aequicor.heartbeat.core.secrets.impl.SecretsConfig

@DependencyGraph(AppScope::class)
internal interface JvmHeartbeatGraph : HeartbeatGraph {
    val secretStorage: SecretStorageInfo

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
    SecretsConfig(isDevelopment = hasLocalSecrets, isInteractionAllowed = allowSecretInteraction),
)
