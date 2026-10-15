package io.aequicor.heartbeat.platform.desktop

private const val HARNESS_COMPILER_WORKER_ARGUMENT = "--heartbeat-harness-compiler-worker"
private const val HARNESS_COMPILER_WORKER_MAIN =
    "io.aequicor.heartbeat.feature.harness.impl.data.script.worker.HarnessCompilerBootstrapKt"

/**
 * A packaged jlink runtime has no Java launcher, so the harness compiler worker process starts through the application
 * launcher in this private mode, before logging, credentials, DI or Compose initialize. The worker takes its heap
 * limit from `JAVA_TOOL_OPTIONS` and talks to its parent over standard streams. Reflection keeps the desktop host
 * independent of feature implementations in the dependency graph.
 */
internal fun runPackagedHarnessCompilerWorker(arguments: Array<String>): Boolean {
    if (arguments.firstOrNull() != HARNESS_COMPILER_WORKER_ARGUMENT) return false
    Class.forName(HARNESS_COMPILER_WORKER_MAIN).getMethod("main", Array<String>::class.java)
        .invoke(null, arguments.drop(1).toTypedArray())
    return true
}
