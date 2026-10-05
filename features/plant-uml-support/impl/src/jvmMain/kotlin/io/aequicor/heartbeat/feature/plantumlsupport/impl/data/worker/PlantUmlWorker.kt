package io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.JvmPlantUmlEngine
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.PlantUmlEngine
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

/**
 * Entry point of the PlantUML worker process, started by [io.aequicor.heartbeat.feature.plantumlsupport.impl.data
 * .ProcessPlantUmlEngine] with a Java launcher, or by the packaged app launcher in its private worker mode. Requests
 * arrive on standard input and replies leave on standard output; anything the engine prints goes to standard error.
 * The worker ends when its input closes or the app's process exits. It refuses to start without the heap cap of
 * [WORKER_HEAP_MIB] (exit code [UNBOUNDED_HEAP_EXIT]): app launcher options override `JAVA_TOOL_OPTIONS`.
 */
public fun main(arguments: Array<String>) {
    require(arguments.isEmpty()) { "InvalidPlantUmlWorkerArguments" }
    // A drawing does not read its input, so a busy worker would outlive a crashed or killed app without this.
    ProcessHandle.current().parent().ifPresent { app ->
        app.onExit().thenRun { Runtime.getRuntime().halt(APP_GONE_EXIT) }
    }
    if (Runtime.getRuntime().maxMemory() > (WORKER_HEAP_MIB + HEAP_SLACK_MIB) * MEBIBYTE) {
        exitProcess(UNBOUNDED_HEAP_EXIT)
    }
    System.setProperty("java.awt.headless", "true")
    val output = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)))
    // Stray prints of PlantUML must not corrupt the replies.
    System.setOut(System.err)
    val diagnostics = DiagnosticsSink()
    Log.init(isDebug = false, sinks = listOf(diagnostics))
    DataInputStream(BufferedInputStream(FileInputStream(FileDescriptor.`in`))).use { input ->
        runPlantUmlWorker(input, output, JvmPlantUmlEngine(), diagnostics)
    }
}

/** Serves requests from [input] one at a time until the host closes it. */
internal fun runPlantUmlWorker(
    input: DataInputStream,
    output: DataOutputStream,
    engine: PlantUmlEngine,
    diagnostics: DiagnosticsSink,
) {
    output.writeInt(PLANTUML_WORKER_READY)
    output.flush()
    while (true) {
        val request = input.readRequest() ?: return
        diagnostics.clear()
        val result = try {
            engine.render(request.source, request.preamble, request.limits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Reaches the host through the diagnostics; the worker stays up for the next drawing.
            log.w(e) { "PlantUML engine failed type=${request.source.type}" }
            PlantUmlResult.Failed(PlantUmlFailure.Internal)
        }
        output.writeReply(PlantUmlWorkerReply(result, diagnostics.drain()))
    }
}

/** Keeps the warnings and errors of the current drawing, so the host logs them in its own log. */
internal class DiagnosticsSink : LogSink {
    private val records = CopyOnWriteArrayList<PlantUmlWorkerDiagnostic>()

    override fun write(level: LogLevel, tag: String, error: Throwable?, message: String) {
        if (level < LogLevel.WARNING || records.size >= MAX_DIAGNOSTICS) return
        val trace = error?.stackTraceToString()?.let { "\n$it" }.orEmpty()
        records += PlantUmlWorkerDiagnostic(level, tag, "$message$trace")
    }

    fun clear() = records.clear()

    fun drain(): List<PlantUmlWorkerDiagnostic> = records.toList().also { records.clear() }
}

private val log = Log.tag("PlantUmlWorker")
private const val APP_GONE_EXIT = 2
private const val UNBOUNDED_HEAP_EXIT = 4

/** The JVM reports a little more than `-Xmx` with some collectors. */
private const val HEAP_SLACK_MIB = 16L
private const val MEBIBYTE = 1024L * 1024L
