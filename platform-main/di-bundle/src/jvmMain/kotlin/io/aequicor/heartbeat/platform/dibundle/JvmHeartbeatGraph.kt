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

/** Creates the application graph. Only the development desktop entry point opts into local credential storage. */
fun createHeartbeatGraph(isDevelopment: Boolean = false): HeartbeatGraph =
    createGraphFactory<JvmHeartbeatGraph.Factory>().create(SecretsConfig(isDevelopment = isDevelopment))
