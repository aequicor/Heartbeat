package io.aequicor.heartbeat.platform.desktop

private const val PLANTUML_WORKER_ARGUMENT = "--heartbeat-plantuml-worker"
private const val PLANTUML_WORKER_MAIN =
    "io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerKt"

/**
 * A packaged jlink runtime has no Java launcher, so the PlantUML worker process starts through the application
 * launcher in this private mode, before logging, credentials, DI or Compose initialize. The worker takes its heap
 * limit from `JAVA_TOOL_OPTIONS` and talks to its parent over standard streams. Reflection keeps the desktop host
 * independent of feature implementations in the dependency graph.
 */
internal fun runPackagedPlantUmlWorker(arguments: Array<String>): Boolean {
    if (arguments.firstOrNull() != PLANTUML_WORKER_ARGUMENT) return false
    require(arguments.size == 1) { "InvalidPlantUmlWorkerArguments" }
    Class.forName(PLANTUML_WORKER_MAIN).getMethod("main", Array<String>::class.java)
        .invoke(null, emptyArray<String>())
    return true
}
