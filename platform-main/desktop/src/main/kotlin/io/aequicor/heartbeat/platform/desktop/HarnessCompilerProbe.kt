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
 * Exercises the compiler shipped in the release image, before opening any user profile. The feature owns
 * compilation and evaluation; this host checks that native window services and the event thread still work
 * afterwards. Reflection preserves the desktop module's dependency boundary, as for the packaged workers.
 */
internal fun handleHarnessCompilerProbe(arguments: Array<String>): Boolean {
    if (arguments.firstOrNull() != HARNESS_PROBE_ARGUMENT) return false
    require(arguments.size == 1) { "InvalidHarnessProbeArguments" }
    Log.init(isDebug = true)
    val directory = Files.createTempDirectory("heartbeat-harness-probe-")
    var pending: CompletableFuture<*>? = null
    var isSuccessful = false
    try {
        val entry = Class.forName(HARNESS_PROBE_CLASS).getMethod("run", String::class.java, String::class.java)
        val version = System.getProperty("heartbeat.app.version", "development")
        val result = entry.invoke(null, directory.toString(), version) as CompletionStage<*>
        val completion = result.toCompletableFuture()
        pending = completion
        check(completion.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) == PROBE_SUCCESS) {
            "HarnessCompilerProbeFailed"
        }
        check(JBR.isWindowDecorationsSupported()) { "HarnessProbeWindowRuntimeUnavailable" }
        EventQueue.invokeAndWait { check(EventQueue.isDispatchThread()) }
        isSuccessful = true
        Log.tag("HarnessHost").i { "Packaged harness compiler and desktop event thread verified" }
    } finally {
        // A timed-out detached compiler can still hold Windows file handles. Its process-level deadline owns
        // termination; removing the directory here would race its writes and could mask the original failure.
        if (pending?.isDone != false) {
            cleanHarnessProbe(directory, isSuccessful)
        } else {
            Log.tag("HarnessHost").w { "Compiler probe still active; temporary files retained after timeout" }
        }
    }
    return true
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
