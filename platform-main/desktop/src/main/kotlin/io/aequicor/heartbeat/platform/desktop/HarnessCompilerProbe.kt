package io.aequicor.heartbeat.platform.desktop

import com.jetbrains.JBR
import io.aequicor.heartbeat.core.logging.Log
import java.awt.EventQueue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * Exercises the compiler shipped in the release image, before opening any user profile: the feature compiles in its
 * worker process, started through this launcher's private worker mode ([runPackagedHarnessCompilerWorker]), and
 * evaluates in this one. This host checks the event thread afterwards, plus native window services on macOS and
 * Windows. Linux uses standard window decorations and does not require JBR's native caption service.
 * Reflection preserves the desktop module's dependency boundary, as for the packaged workers.
 */
internal fun handleHarnessCompilerProbe(arguments: Array<String>): Boolean {
    if (arguments.firstOrNull() != HARNESS_PROBE_ARGUMENT) return false
    Log.init(isDebug = true)
    val directory = Files.createTempDirectory("heartbeat-harness-probe-")
    var pending: CompletableFuture<*>? = null
    var isSuccessful = false
    try {
        val entry = Class.forName(HARNESS_PROBE_CLASS).getMethod(
            "run",
            String::class.java,
            String::class.java,
            Array<String>::class.java,
        )
        val version = System.getProperty("heartbeat.app.version", "development")
        val result = entry.invoke(
            null,
            directory.toString(),
            version,
            arguments.drop(1).toTypedArray(),
        ) as CompletionStage<*>
        val completion = result.toCompletableFuture()
        pending = completion
        check(completion.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) == PROBE_SUCCESS) {
            "HarnessCompilerProbeFailed"
        }
        verifyHarnessWindowRuntime()
        EventQueue.invokeAndWait { check(EventQueue.isDispatchThread()) }
        isSuccessful = true
        Log.tag("HarnessHost").i { "Packaged harness compiler and desktop event thread verified" }
    } finally {
        // A timed-out probe can still own a compilation whose worker writes into this directory until its budget
        // kills it; removing the directory here would race those writes and could mask the original failure.
        if (pending?.isDone != false) {
            cleanHarnessProbe(directory, isSuccessful)
        } else {
            Log.tag("HarnessHost").w { "Compiler probe still active; temporary files retained after timeout" }
        }
    }
    return true
}

/** Matches the desktop host's native caption requirement without rejecting Linux's standard decorations. */
internal fun verifyHarnessWindowRuntime(
    osName: String = System.getProperty("os.name").orEmpty(),
    isSupported: () -> Boolean = JBR::isWindowDecorationsSupported,
) {
    if (osName.startsWith("Mac") || osName.startsWith("Windows")) {
        check(isSupported()) { "HarnessProbeWindowRuntimeUnavailable" }
    }
}

private fun cleanHarnessProbe(directory: Path, shouldFail: Boolean) {
    try {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    } catch (error: IOException) {
        if (shouldFail) throw error
        Log.tag("HarnessHost").w(error) { "Compiler probe temporary directory cleanup failed" }
    }
}

private const val HARNESS_PROBE_ARGUMENT = "--heartbeat-harness-probe"
private const val HARNESS_PROBE_CLASS = "io.aequicor.heartbeat.feature.harness.impl.data.script.HarnessHostProbe"
private const val PROBE_SUCCESS = "heartbeat-harness-probe-ok-v1"
private const val PROBE_TIMEOUT_SECONDS = 240L
