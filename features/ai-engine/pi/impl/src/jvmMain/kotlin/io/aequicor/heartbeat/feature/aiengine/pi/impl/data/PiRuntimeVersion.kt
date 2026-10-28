package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit

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
            stopProbe(process)
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

private fun stopProbe(process: Process) {
    try {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
    } catch (e: SecurityException) {
        log.w(e.withoutDetails()) { "Version probe descendants unavailable" }
    } catch (e: UnsupportedOperationException) {
        log.w(e.withoutDetails()) { "Version probe descendants unsupported" }
    } finally {
        try {
            process.destroyForcibly()
        } finally {
            try {
                process.inputStream.close()
            } catch (e: IOException) {
                log.w(e.withoutDetails()) { "Version probe output could not be closed" }
            }
        }
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
private const val MAX_VERSION_BYTES = 4096
