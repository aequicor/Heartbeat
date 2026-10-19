package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostBootstrapScope

/** This scope has only dispatcher bindings; it cannot construct app/profile stores or feature runtimes. */
@DependencyGraph(HostBootstrapScope::class)
internal interface DesktopHostGraph {
    val dispatchers: DispatcherProvider
}

/** Bootstrap IO and Main without a HeartbeatGraph, for execution-owner clients and the foreground worker host. */
public fun createDesktopHostDispatchers(): DispatcherProvider = createGraph<DesktopHostGraph>().dispatchers
