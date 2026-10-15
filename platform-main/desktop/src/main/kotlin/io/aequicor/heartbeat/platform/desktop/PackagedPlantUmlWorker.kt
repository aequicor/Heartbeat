package io.aequicor.heartbeat.platform.desktop

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path

private const val PLANTUML_WORKER_ARGUMENT = "--heartbeat-plantuml-worker"
private const val PLANTUML_WORKER_MAIN =
    "io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerKt"

/**
 * A packaged jlink runtime has no Java launcher, so the PlantUML worker process starts through the application
 * launcher in this private mode, before logging, credentials, DI or Compose initialize. The worker takes its heap
 * limit from `JAVA_TOOL_OPTIONS` and talks to its parent over standard streams. The only argument is the prepared
 * engine JAR, rechecked against its compiled SHA-256 pin before loading. The loader contains only the worker's
 * dependencies and uses the platform loader as parent, so the host never resolves PlantUML classes itself.
 * Reflection keeps the desktop host independent of feature implementations in the dependency graph.
 */
internal fun runPackagedPlantUmlWorker(arguments: Array<String>): Boolean {
    if (arguments.firstOrNull() != PLANTUML_WORKER_ARGUMENT) return false
    require(arguments.size == 2) { "InvalidPlantUmlWorkerArguments" }
    val runtime = Path.of(arguments[1])
    val bridge = Class.forName("io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerLauncherKt")
    val classpath = bridge.getMethod("plantUmlWorkerClasspath", Path::class.java).invoke(null, runtime) as String
    val urls = classpath.split(File.pathSeparator).map { Path.of(it).toUri().toURL() }.toTypedArray()
    URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        try {
            thread.contextClassLoader = loader
            Class.forName(PLANTUML_WORKER_MAIN, true, loader).getMethod("main", Array<String>::class.java)
                .invoke(null, emptyArray<String>())
        } finally {
            thread.contextClassLoader = previous
        }
    }
    return true
}
