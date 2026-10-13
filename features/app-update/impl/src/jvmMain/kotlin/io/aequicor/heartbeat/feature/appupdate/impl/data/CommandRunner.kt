package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
import io.aequicor.heartbeat.feature.appupdate.impl.domain.updateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.withoutDetails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Runs the OS tools of an update (`hdiutil`, `ditto`) and starts the installer helper. */
internal class CommandRunner(private val io: CoroutineDispatcher) {
    private val log = Log.tag("AppUpdate")

    /**
     * Runs [command] to its end within [timeout]; its output is discarded (it names the user's paths). A failure to
     * start, a non-zero exit or the timeout fail with [AppUpdateFailure.InstallFailed]; cancellation stops it.
     */
    suspend fun run(command: List<String>, timeout: Duration = DEFAULT_TIMEOUT) {
        val tool = File(command.first()).name
        val process = start(
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD),
        )
        try {
            val isFinished = runInterruptible(
                io,
            ) { process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
            if (!isFinished) {
                log.w { "update tool timed out tool=$tool" }
                updateFailure(AppUpdateFailure.InstallFailed)
            }
            if (process.exitValue() != 0) {
                log.w { "update tool failed tool=$tool exit=${process.exitValue()}" }
                updateFailure(AppUpdateFailure.InstallFailed)
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * Starts the installer helper [command], which outlives this process; its output is appended to [output].
     * Returns once it runs.
     */
    fun startDetached(command: List<String>, output: Path) {
        start(
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(output.toFile()))
                .redirectInput(ProcessBuilder.Redirect.from(File(NULL_DEVICE))),
        )
        log.i { "update helper started tool=${File(command.first()).name}" }
    }

    // The process message names the user's paths: only the kind of failure travels on, so it is not the cause.
    @Suppress("SwallowedException")
    private fun start(builder: ProcessBuilder): Process = try {
        builder.start()
    } catch (e: IOException) {
        log.w(e.withoutDetails()) { "update tool could not be started tool=${File(builder.command().first()).name}" }
        throw AppUpdateException(AppUpdateFailure.InstallFailed, e.withoutDetails())
    }

    private companion object {
        val DEFAULT_TIMEOUT = 5.minutes
        val NULL_DEVICE: String = if (File.separatorChar == '\\') "NUL" else "/dev/null"
    }
}
