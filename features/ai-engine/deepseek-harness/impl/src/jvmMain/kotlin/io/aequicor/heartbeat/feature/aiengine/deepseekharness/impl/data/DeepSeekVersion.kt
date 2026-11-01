package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

private val log = Log.tag("DeepSeekVersion")

/**
 * `dsh --version`, bounded and cancellable; an executable that does not answer has no known version. The probe only
 * reads the reported version: it never boots a profile, starts a session or touches the user's login.
 */
internal suspend fun probeDeepSeekVersion(
    dispatchers: DispatcherProvider,
    executable: String,
    timeoutMillis: Long,
): String? = withContext(dispatchers.io) {
    try {
        withTimeoutOrNull(timeoutMillis) {
            val process = ProcessBuilder(executable, "--version")
                .redirectErrorStream(true)
                .start()
            try {
                process.outputStream.close()
                val exit = runInterruptible { process.waitFor() }
                if (exit == 0) {
                    parseDeepSeekVersion(
                        process.inputStream.readNBytes(MAX_VERSION_BYTES).decodeToString(),
                    )
                } else {
                    null
                }
            } finally {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                process.inputStream.close()
                process.errorStream.close()
            }
        }
    } catch (error: IOException) {
        log.w(error) { "DeepSeek Harness version could not be read" }
        null
    }
}

/** The version `dsh --version` prints (`0.2.1-rc.5`), or null. */
internal fun parseDeepSeekVersion(output: String): String? = VersionPattern.find(output)?.value

private val VersionPattern = Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?""")

/** `--version` is one short line; the cap keeps a misbehaving launcher from filling memory. */
private const val MAX_VERSION_BYTES = 4096
