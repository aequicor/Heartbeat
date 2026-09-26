package io.aequicor.heartbeat.platform.dibundle

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

@DependencyGraph(AppScope::class)
internal interface AndroidHeartbeatGraph : HeartbeatGraph {
    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides context: Context,
        ): AndroidHeartbeatGraph
    }
}

/**
 * Creates the application graph. Called once by the platform entry point (`Application.onCreate`).
 * Only the application context is kept: storages resolve their files from it.
 */
fun createHeartbeatGraph(context: Context): HeartbeatGraph =
    createGraphFactory<AndroidHeartbeatGraph.Factory>().create(context.applicationContext)
