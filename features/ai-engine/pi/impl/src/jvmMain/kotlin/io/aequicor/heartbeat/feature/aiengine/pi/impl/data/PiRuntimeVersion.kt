package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * `pi --version`, offline, bounded and cancellable. The caller uses its IO dispatcher. A temporary empty home
 * and cwd prevent a launcher wrapper from inheriting project extensions or saved MCP servers during the probe.
 * The selected executable alone is queried; failure never falls back to another installation.
 */
internal suspend fun readPiRuntimeVersion(
    startup: PiStartup,
    start: (ProcessBuilder) -> Process = {
        it.start()
    },
): String? {
    val executable = startup.executable ?: return null
    var directory: Path? = null
    return try {
        val root = Files.createTempDirectory("heartbeat-pi-version-")
        directory = root
        val builder = ProcessBuilder(executable.toString(), "--version")
            .directory(root.toFile())
            .redirectErrorStream(true)
        applyPiEnvironment(builder.environment(), startup)
        builder.environment()["PI_CODING_AGENT_DIR"] = root.toString()
        builder.environment()["PI_OFFLINE"] = "1"
        builder.environment()["PI_SKIP_VERSION_CHECK"] = "1"
        val process = start(builder)
        try {
            process.outputStream.close()
            val isDone = runInterruptible { process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            if (isDone && process.exitValue() == 0) parsePiVersion(probeOutput(process)) else null
        } finally {
            withContext(NonCancellable) { stopProbe(process) }
        }
    } catch (e: IOException) {
        log.w(e.withoutDetails()) { "Pi version could not be read" }
        null
    } finally {
        directory?.let(::removeProbeDirectory)
    }
}

/** Reads only buffered bytes; launcher children can keep stdout open after their parent exits. */
private suspend fun probeOutput(process: Process): String = runInterruptible {
    process.inputStream.readNBytes(process.inputStream.available().coerceAtMost(MAX_VERSION_BYTES)).decodeToString()
}

/** Reaps the probe before its cwd is removed, including when the version request was cancelled. */
private fun stopProbe(process: Process) {
    val children = probeDescendants(process)
    try {
        children.forEach { it.destroyForcibly() }
    } catch (e: SecurityException) {
        log.w(e.withoutDetails()) { "Version probe descendants could not be stopped" }
    } finally {
        try {
            process.destroyForcibly()
            awaitProbeExit(process, children)
        } finally {
            try {
                process.inputStream.close()
            } catch (e: IOException) {
                log.w(e.withoutDetails()) { "Version probe output could not be closed" }
            }
        }
    }
}

private fun probeDescendants(process: Process): List<ProcessHandle> = try {
    process.descendants().use { it.toList() }
} catch (e: SecurityException) {
    log.w(e.withoutDetails()) { "Version probe descendants unavailable" }
    emptyList()
} catch (e: UnsupportedOperationException) {
    log.w(e.withoutDetails()) { "Version probe descendants unsupported" }
    emptyList()
}

/** Parent and descendants share one bounded shutdown budget instead of one timeout per child. */
private fun awaitProbeExit(process: Process, children: List<ProcessHandle>) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_TIMEOUT_SECONDS)
    if (!process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        log.w { "Version probe did not stop within the shutdown budget" }
    }
    try {
        for (child in children) {
            if (child.isAlive) child.onExit().get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        }
    } catch (e: TimeoutException) {
        log.w(e.withoutDetails()) { "Version probe descendants did not stop within the shutdown budget" }
    } catch (e: ExecutionException) {
        log.w(e.withoutDetails()) { "Version probe descendant exit could not be awaited" }
    } catch (e: UnsupportedOperationException) {
        log.w(e.withoutDetails()) { "Version probe descendant exit unsupported" }
    } catch (e: SecurityException) {
        log.w(e.withoutDetails()) { "Version probe descendant exit unavailable" }
    }
}

private fun removeProbeDirectory(directory: Path) {
    try {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    } catch (e: IOException) {
        log.w(e.withoutDetails()) { "Version probe directory could not be removed" }
    }
}

private val log = Log.tag("PiRuntimeVersion")
private const val VERSION_TIMEOUT_SECONDS = 10L
private const val SHUTDOWN_TIMEOUT_SECONDS = 2L
private const val MAX_VERSION_BYTES = 4096
