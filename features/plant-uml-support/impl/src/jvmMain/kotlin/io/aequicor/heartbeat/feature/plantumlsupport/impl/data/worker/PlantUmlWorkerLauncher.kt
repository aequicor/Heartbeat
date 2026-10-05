package io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker

import net.sourceforge.plantuml.SourceStringReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Starts a PlantUML worker process; tests substitute other JVM options. */
internal fun interface PlantUmlWorkerLauncher {
    fun start(): Process
}

/**
 * Starts the worker with the runtime's Java launcher and the app's classpath. A packaged jlink runtime has no Java
 * launcher, so there the app launcher (`jpackage.app-path`) starts in its private worker mode, routed by the desktop
 * entry point before anything else initializes, and takes [jvmOptions] from `JAVA_TOOL_OPTIONS`. The worker runs in
 * the temporary directory with only the environment a JVM needs: none of the app's secrets reach diagram code.
 */
internal class JavaPlantUmlWorkerLauncher(private val jvmOptions: List<String> = WORKER_JVM_OPTIONS) :
    PlantUmlWorkerLauncher {
    override fun start(): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java")
        val (command, toolOptions) = if (Files.isRegularFile(java)) {
            listOf(java.toString()) + jvmOptions + listOf("-cp", workerClasspath(), WORKER_MAIN) to null
        } else {
            val packaged = System.getProperty("jpackage.app-path")?.let(Path::of)
            check(packaged != null && Files.isRegularFile(packaged)) { "PlantUmlWorkerRuntimeUnavailable" }
            listOf(packaged.toString(), WORKER_ARGUMENT) to jvmOptions.joinToString(" ")
        }
        val builder = ProcessBuilder(command)
            .directory(File(System.getProperty("java.io.tmpdir")))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        val environment = builder.environment()
        val kept = workerEnvironment(environment.toMap())
        environment.clear()
        environment.putAll(kept)
        toolOptions?.let { environment[JAVA_TOOL_OPTIONS] = it }
        return builder.start()
    }

    private companion object {
        const val WORKER_MAIN = "io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.PlantUmlWorkerKt"
        const val WORKER_ARGUMENT = "--heartbeat-plantuml-worker"
        const val JAVA_TOOL_OPTIONS = "JAVA_TOOL_OPTIONS"
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}

/**
 * A small heap that ends the worker, not the app, when a source grows without bound (exit code
 * [WORKER_OUT_OF_MEMORY_EXIT]); it still fits the largest canvas the limits allow. The JVM's own messages go to
 * standard error, away from the replies. No display, no perf data files.
 */
internal val WORKER_JVM_OPTIONS: List<String> = listOf(
    "-Xmx384m",
    "-XX:+ExitOnOutOfMemoryError",
    "-XX:+UseSerialGC",
    "-XX:-UsePerfData",
    "-XX:+DisplayVMOutputToStderr",
    "-Djava.awt.headless=true",
)

/** HotSpot's exit code for `-XX:+ExitOnOutOfMemoryError`. */
internal const val WORKER_OUT_OF_MEMORY_EXIT: Int = 3

/** Variables of [parent] the worker keeps: temporary directories, locale and the Windows system paths. */
internal fun workerEnvironment(parent: Map<String, String>): Map<String, String> =
    parent.filterKeys { name -> WorkerEnvironment.any { it.equals(name, ignoreCase = true) } }

private val WorkerEnvironment = setOf("TMPDIR", "TEMP", "TMP", "LANG", "LC_ALL", "LC_CTYPE", "SystemRoot", "windir")

/** The app's classpath, and the jars of the worker's own classes when a class loader hides them from it. */
private fun workerClasspath(): String {
    val paths = linkedSetOf<String>()
    paths += System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }
    listOf(PlantUmlWorkerLauncher::class.java, SourceStringReader::class.java, Unit::class.java).forEach { type ->
        type.protectionDomain?.codeSource?.location?.let { paths += Path.of(it.toURI()).toString() }
    }
    return paths.joinToString(File.pathSeparator)
}
