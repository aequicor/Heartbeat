package io.aequicor.heartbeat.feature.harness.impl.data.script.worker

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptBase
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import okio.ByteString
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/** Starts a harness compiler worker process; tests substitute other JVM options or another program. */
internal fun interface HarnessCompilerLauncher {
    fun start(): Process
}

/**
 * Starts the worker with the runtime's Java launcher and the app's classpath. A packaged jlink runtime has no Java
 * launcher, so there the app launcher (`jpackage.app-path`) starts in its private worker mode, routed by the desktop
 * entry point before anything else initializes, and takes [jvmOptions] from `JAVA_TOOL_OPTIONS`. The worker runs in
 * the temporary directory with only the environment a JVM needs: none of the app's secrets reach the compiler.
 */
internal class JavaHarnessCompilerLauncher(private val jvmOptions: List<String> = WORKER_JVM_OPTIONS) :
    HarnessCompilerLauncher {
    override fun start(): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java")
        val (command, toolOptions) = if (Files.isRegularFile(java)) {
            listOf(java.toString()) + jvmOptions + listOf("-cp", workerClasspath(), WORKER_MAIN) to null
        } else {
            val packaged = System.getProperty("jpackage.app-path")?.let(Path::of)
            check(packaged != null && Files.isRegularFile(packaged)) { "HarnessCompilerWorkerRuntimeUnavailable" }
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
        const val WORKER_MAIN = "io.aequicor.heartbeat.feature.harness.impl.data.script.worker.HarnessCompilerWorkerKt"
        const val WORKER_ARGUMENT = "--heartbeat-harness-compiler-worker"
        const val JAVA_TOOL_OPTIONS = "JAVA_TOOL_OPTIONS"
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}

/**
 * A heap that ends the worker, not the app, when a source makes the compiler grow without bound (exit code
 * [WORKER_OUT_OF_MEMORY_EXIT]). The JVM's own messages go to standard error, away from the replies. No display, no
 * perf data files.
 */
internal val WORKER_JVM_OPTIONS: List<String> = listOf(
    "-Xmx${WORKER_HEAP_MIB}m",
    "-XX:+ExitOnOutOfMemoryError",
    "-XX:+UseSerialGC",
    "-XX:-UsePerfData",
    "-XX:+DisplayVMOutputToStderr",
    "-Djava.awt.headless=true",
)

/** The worker's heap cap in MiB; the worker checks it on start. */
internal const val WORKER_HEAP_MIB: Long = 512

/** The worker's exit code when it finds no heap cap of [WORKER_HEAP_MIB]. */
internal const val WORKER_UNCAPPED_HEAP_EXIT: Int = 4

/** HotSpot's exit code for `-XX:+ExitOnOutOfMemoryError`. */
internal const val WORKER_OUT_OF_MEMORY_EXIT: Int = 3

/** The worker's exit code after an error the compiler cannot recover from. */
internal const val WORKER_FATAL_EXIT: Int = 5

/** Variables of [parent] the worker keeps: temporary directories, locale and the Windows system paths. */
internal fun workerEnvironment(parent: Map<String, String>): Map<String, String> =
    parent.filterKeys { name -> WorkerEnvironment.any { it.equals(name, ignoreCase = true) } }

/** core:logging's logging backend, not visible to this module at compile time. */
private const val NAPIER = "io.github.aakira.napier.Napier"
private val log = Log.tag("HarnessCompilerLauncher")

private val WorkerEnvironment = setOf("TMPDIR", "TEMP", "TMP", "LANG", "LC_ALL", "LC_CTYPE", "SystemRoot", "windir")

/** The app's classpath, and the jars of the worker's own classes when a class loader hides them from it. */
private fun workerClasspath(): String {
    val paths = linkedSetOf<String>()
    paths += System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }
    val loader = HarnessCompilerLauncher::class.java.classLoader
    val types = mutableListOf<Class<*>>(
        HarnessCompilerLauncher::class.java,
        HarnessScriptBase::class.java,
        SessionRef::class.java,
        WakeId::class.java,
        Log::class.java,
        Unit::class.java,
        CoroutineScope::class.java,
        JsonObject::class.java,
        KSerializer::class.java,
        ByteString::class.java,
        ScriptCompilationConfiguration::class.java,
        KJvmCompiledScript::class.java,
        BasicJvmScriptingHost::class.java,
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler::class.java,
    )
    try {
        types += Class.forName(NAPIER, false, loader)
    } catch (e: ClassNotFoundException) {
        // Only the fallback misses it; the classpath above normally holds it.
        log.w(e) { "Harness compiler worker classpath fallback misses the logging backend" }
    }
    types.forEach { type ->
        type.protectionDomain?.codeSource?.location?.let { paths += Path.of(it.toURI()).toString() }
    }
    return paths.joinToString(File.pathSeparator)
}
