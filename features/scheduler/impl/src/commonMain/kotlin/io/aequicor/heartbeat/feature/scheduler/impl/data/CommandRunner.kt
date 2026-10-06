package io.aequicor.heartbeat.feature.scheduler.impl.data

import io.aequicor.heartbeat.feature.scheduler.api.TaskProcess
import kotlin.time.Duration

/** How a background command ended: [exitCode] is null when it timed out; [output] is head and tail of its output. */
internal data class CommandOutcome(val exitCode: Int?, val output: String) {
    override fun toString(): String = "CommandOutcome(exitCode=${exitCode ?: "timeout"}, output=${output.length} chars)"
}

/** Runs a shell command in a project directory; only Desktop has local processes. */
internal interface CommandRunner {
    /** False on platforms without local processes. */
    val isAvailable: Boolean

    /** User code cannot run until the native identity callback has completed successfully. */
    val isStartTracked: Boolean get() = false

    /** Runs [command] in [directory] until it exits or [timeout] passes; the process tree dies with cancellation. */
    suspend fun run(directory: String, command: String, timeout: Duration): CommandOutcome

    /**
     * Records native identity before releasing the command, then updates it when POSIX descendants are observed.
     * Graphs durably retain each snapshot to reconcile descendants which leave their original process group.
     */
    suspend fun runTracked(
        directory: String,
        command: String,
        timeout: Duration,
        onStarted: suspend (TaskProcess) -> Unit,
    ): CommandOutcome = run(directory, command, timeout)

    /** True only when that exact process is confirmed absent. Unknown identity is never safe to repeat. */
    suspend fun isStopped(process: TaskProcess): Boolean = false

    /** Stops the exact original process tree, returning only confirmed absence. */
    suspend fun stop(process: TaskProcess): Boolean = false
}
