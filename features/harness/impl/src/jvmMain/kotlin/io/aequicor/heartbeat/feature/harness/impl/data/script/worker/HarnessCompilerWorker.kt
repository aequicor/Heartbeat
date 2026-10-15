package io.aequicor.heartbeat.feature.harness.impl.data.script.worker

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.impl.data.script.HarnessScriptClasspath
import io.aequicor.heartbeat.feature.harness.impl.data.script.harnessCompilerConfiguration
import io.aequicor.heartbeat.feature.harness.impl.data.script.harnessDiagnostics
import io.aequicor.heartbeat.feature.harness.impl.data.script.validateHarnessSource
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.rethrowFatalHarnessFailure
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.saveToJar
import kotlin.system.exitProcess

/**
 * Entry point of the harness compiler worker process, started by [io.aequicor.heartbeat.feature.harness.impl.data
 * .script.ProcessHarnessCompiler] with a Java launcher, or by the packaged app launcher in its private worker mode.
 * Requests arrive on standard input and replies leave on standard output; anything the compiler prints goes to
 * standard error. The worker ends when its input closes or the app's process exits, and halts on a fatal error
 * ([WORKER_FATAL_EXIT]). It refuses to start without the heap cap of [WORKER_HEAP_MIB] (exit code
 * [WORKER_UNCAPPED_HEAP_EXIT]): app launcher options override `JAVA_TOOL_OPTIONS`.
 */
public fun main(arguments: Array<String>) {
    require(arguments.isEmpty()) { "InvalidHarnessCompilerWorkerArguments" }
    // A compilation does not read its input, so a busy worker would outlive a crashed or killed app without this.
    ProcessHandle.current().parent().ifPresent { app ->
        app.onExit().thenRun { Runtime.getRuntime().halt(APP_GONE_EXIT) }
    }
    if (Runtime.getRuntime().maxMemory() > (WORKER_HEAP_MIB + HEAP_SLACK_MIB) * MEBIBYTE) {
        exitProcess(WORKER_UNCAPPED_HEAP_EXIT)
    }
    // Errors the compiler cannot recover from end the worker; the host sees its exit and starts a fresh one.
    Thread.setDefaultUncaughtExceptionHandler { _, _ -> Runtime.getRuntime().halt(WORKER_FATAL_EXIT) }
    val output = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)))
    // Stray prints of the compiler must not corrupt the replies.
    System.setOut(System.err)
    DataInputStream(BufferedInputStream(FileInputStream(FileDescriptor.`in`))).use { input ->
        runHarnessCompilerWorker(input, output, HarnessScriptClasspath())
    }
}

/** Serves requests from [input] one at a time until the host closes it. */
internal fun runHarnessCompilerWorker(
    input: DataInputStream,
    output: DataOutputStream,
    classpath: HarnessScriptClasspath,
) {
    output.writeInt(HARNESS_COMPILER_WORKER_READY)
    output.flush()
    while (true) {
        val request = input.readRequest() ?: return
        val reply = try {
            compileHarnessCode(request, classpath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.rethrowFatalHarnessFailure()
            // Compiler exceptions may quote the source; the worker's log stays in this process.
            log.w(e) { "harness compiler failed kind=${request.kind}" }
            HarnessCompilerReply.Internal
        }
        output.writeReply(reply)
    }
}

/** Compiles with K2 in the calling thread; a successful jar goes to the request's output file. */
private fun compileHarnessCode(
    request: HarnessCompilerRequest,
    classpath: HarnessScriptClasspath,
): HarnessCompilerReply {
    val failures = validateHarnessSource(request.source)
    if (failures.isNotEmpty()) return HarnessCompilerReply.Failed(failures)
    val result = compileBlocking(request, classpath)
    result.reports.forEach { it.exception?.rethrowFatalHarnessFailure() }
    return when (result) {
        is ResultWithDiagnostics.Failure -> HarnessCompilerReply.Failed(result.reports.harnessDiagnostics())

        is ResultWithDiagnostics.Success -> {
            (result.value as KJvmCompiledScript).saveToJar(File(request.output))
            HarnessCompilerReply.Compiled(result.reports.harnessDiagnostics())
        }
    }
}

/** JvmScriptCompiler never suspends: its whole compilation completes synchronously in the calling thread. */
private fun compileBlocking(
    request: HarnessCompilerRequest,
    classpath: HarnessScriptClasspath,
): ResultWithDiagnostics<CompiledScript> {
    val compiler = BasicJvmScriptingHost().compiler
    val source = StringScriptSource(request.source, "HarnessCode.kts")
    val configuration = harnessCompilerConfiguration(request.kind, classpath)
    var outcome: Result<ResultWithDiagnostics<CompiledScript>>? = null
    val completion = Continuation<ResultWithDiagnostics<CompiledScript>>(EmptyCoroutineContext) { outcome = it }
    suspend { compiler(source, configuration) }.startCoroutine(completion)
    return checkNotNull(outcome) { "Harness compiler suspended unexpectedly" }.getOrThrow()
}

private val log = Log.tag("HarnessCompilerWorker")
private const val APP_GONE_EXIT = 2

/** The JVM reports a little more than `-Xmx` with some collectors. */
private const val HEAP_SLACK_MIB = 16L
private const val MEBIBYTE = 1024L * 1024L
