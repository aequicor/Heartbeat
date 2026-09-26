package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph

@DependencyGraph(AppScope::class)
internal interface JvmHeartbeatGraph : HeartbeatGraph

/** Creates the application graph. Called once by the platform entry point. */
fun createHeartbeatGraph(): HeartbeatGraph = createGraph<JvmHeartbeatGraph>()
