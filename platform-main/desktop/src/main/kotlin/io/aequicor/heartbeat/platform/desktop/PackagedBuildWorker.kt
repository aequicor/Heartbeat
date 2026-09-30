package io.aequicor.heartbeat.platform.desktop

import java.nio.file.Files
import java.nio.file.Path

private const val BUILD_WORKER_ARGUMENT = "--heartbeat-worktree-build-worker"
private const val BUILD_WORKER_MAIN =
    "io.aequicor.heartbeat.feature.worktreemode.impl.data.worker.WorktreeBuildWorkerKt"

/**
 * A packaged jlink runtime has no Java launcher. Its application launcher routes this private worker mode
 * before logging, credentials, DI or Compose initialize. The worker validates its own persisted job identity.
 * Reflection keeps the desktop host independent of feature implementations in the dependency graph.
 */
internal fun runPackagedBuildWorker(arguments: Array<String>): Boolean {
    if (arguments.firstOrNull() != BUILD_WORKER_ARGUMENT) return false
    require(arguments.size == 2) { "InvalidBuildWorkerArguments" }
    val directory = Path.of(arguments[1])
    require(directory.isAbsolute && Files.isRegularFile(directory.resolve("job.json"))) {
        "InvalidBuildWorkerDirectory"
    }
    Class.forName(BUILD_WORKER_MAIN).getMethod("main", Array<String>::class.java)
        .invoke(null, arrayOf(directory.toString()))
    return true
}
