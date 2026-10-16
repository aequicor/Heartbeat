package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Runs a program with exact arguments in a saved local project, outside agent turns and scheduler actions, for
 * features whose already reviewed code owns the command (harness mini-app buttons). Desktop only.
 *
 * It grants no authority: callers check their own admission and review before calling. Arguments reach the program
 * unchanged, without shell expansion or splitting; on Windows words that `cmd.exe` would parse again for a batch
 * program (`& | < > ^ % ! ( ) "`, line breaks) are refused as [LocalCommandOutcome.Invalid]. Secret-looking
 * environment variables are withheld, stdin is closed and the whole process tree dies on timeout or cancellation.
 * Programs, arguments and output are never logged.
 */
public interface LocalCommands {
    /** False on platforms without local processes or projects. */
    public val isAvailable: Boolean

    /** Runs [request] until the program exits, [LocalCommandRequest.timeout] passes or the caller is cancelled. */
    public suspend fun run(request: LocalCommandRequest): LocalCommandOutcome
}

/**
 * One run of [program] with [arguments] in the directory of [workspace]. A relative [program] is resolved by the
 * platform shell (`./gradlew` on POSIX, `.\gradlew.bat` on Windows run from the project directory).
 */
public data class LocalCommandRequest(
    val workspace: WorkspaceRef,
    val program: String,
    val arguments: List<String> = emptyList(),
    val timeout: Duration = LocalCommandLimits.DEFAULT_TIMEOUT,
) {
    init {
        require(program.isNotBlank()) { "A command needs a program" }
        require(timeout.isPositive() && timeout <= LocalCommandLimits.MAX_TIMEOUT) { "Unsupported command timeout" }
        require(arguments.size <= LocalCommandLimits.ARGUMENTS) { "Too many command arguments" }
        require((arguments + program).none { '\u0000' in it }) { "Command text cannot contain NUL" }
    }

    override fun toString(): String = "LocalCommandRequest(arguments=${arguments.size}, timeout=$timeout)"
}

/** How a [LocalCommandRequest] ended. Output is user data: head and tail of stdout and stderr, never logged. */
public sealed interface LocalCommandOutcome {
    /** The program exited with [exitCode]. */
    public data class Exited(val exitCode: Int, val output: String) : LocalCommandOutcome {
        override fun toString(): String = "LocalCommandOutcome.Exited(exitCode=$exitCode, output=***)"
    }

    /** The program ran longer than its timeout and its process tree was killed. */
    public data class TimedOut(val output: String) : LocalCommandOutcome {
        override fun toString(): String = "LocalCommandOutcome.TimedOut(output=***)"
    }

    /** The project is unknown or no longer on disk. */
    public data object NoProject : LocalCommandOutcome

    /** This platform cannot pass the words unchanged (Windows: `cmd.exe` specials, `"`, empty words). */
    public data object Invalid : LocalCommandOutcome

    /** This platform has no local processes. */
    public data object Unsupported : LocalCommandOutcome
}

/** Bounds of [LocalCommandRequest]. */
public object LocalCommandLimits {
    /** Timeout when the caller does not choose one. */
    public val DEFAULT_TIMEOUT: Duration = 1.minutes

    /** Longest allowed run. */
    public val MAX_TIMEOUT: Duration = 30.minutes

    /** Most arguments of one program. */
    public const val ARGUMENTS: Int = 64
}
