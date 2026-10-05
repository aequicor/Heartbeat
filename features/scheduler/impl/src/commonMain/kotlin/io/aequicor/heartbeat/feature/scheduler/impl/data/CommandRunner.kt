package io.aequicor.heartbeat.feature.scheduler.impl.data

import kotlin.time.Duration

/** How a background command ended: [exitCode] is null when it timed out; [output] is head and tail of its output. */
internal data class CommandOutcome(val exitCode: Int?, val output: String) {
    override fun toString(): String = "CommandOutcome(exitCode=${exitCode ?: "timeout"}, output=${output.length} chars)"
}

/** Runs a shell command in a project directory; only Desktop has local processes. */
internal interface CommandRunner {
    /** False on platforms without local processes. */
    val isAvailable: Boolean

    /** Runs [command] in [directory] until it exits or [timeout] passes; the process tree dies with cancellation. */
    suspend fun run(directory: String, command: String, timeout: Duration): CommandOutcome
}
