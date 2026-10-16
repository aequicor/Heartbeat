package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.ItemName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A program with arguments fixed in the approved source. An argument that is exactly `{name}` is replaced by the
 * value of parameter `name`, which must fully match its pattern in [parameters]; nothing else is substituted and the
 * words are not expanded or split. Windows resolves [program] only to an external application or `.bat`/`.cmd`,
 * never a PowerShell cmdlet, function or `.ps1` script; wildcard or backtick syntax in [program] is refused.
 * Empty words, `cmd.exe` specials (`& | < > ^ % ! ( ) "`, line breaks), and words ending in `\` that also contain
 * whitespace or `, ; =` are refused as invalid on Windows. Other arguments containing `, ; =` stay one argument.
 * A relative [program] runs from the project directory (`./gradlew`, `.\gradlew.bat`). Pass the trusted
 * `ScriptUiCall.workspace`, never a project built from input.
 */
public data class ScriptCommand(
    val program: String,
    val arguments: List<String> = emptyList(),
    val parameters: Map<String, Regex> = emptyMap(),
    val timeout: Duration = 1.minutes,
) {
    override fun toString(): String = "ScriptCommand(arguments=${arguments.size}, timeout=$timeout)"
}

/** How [ScriptCommands.run] ended. Output is the head and tail of stdout and stderr; never log it. */
public sealed interface ScriptCommandResult {
    /** The program exited with [exitCode]; a non-zero code is an ordinary result, not a script failure. */
    public data class Exited(val exitCode: Int, val output: String) : ScriptCommandResult {
        override fun toString(): String = "ScriptCommandResult.Exited(exitCode=$exitCode, output=***)"
    }

    /** The program ran longer than its declared timeout; its process tree was killed. */
    public data class TimedOut(val output: String) : ScriptCommandResult {
        override fun toString(): String = "ScriptCommandResult.TimedOut(output=***)"
    }

    /** Nothing ran, for [reason]. */
    public data class Refused(val reason: ScriptCommandRefusal) : ScriptCommandResult
}

/** Why [ScriptCommands.run] started nothing. */
public enum class ScriptCommandRefusal {
    /** Mini-app commands are switched off in this application. */
    Disabled,

    /** This platform has no local processes (mobile). */
    Unsupported,

    /** No project was given, or it is unknown or no longer on disk. */
    NoProject,

    /** The project is outside this harness's project scope. */
    OutsideScope,

    /** This activation declared no command with that name. */
    NotDefined,

    /** A parameter is missing, unknown, too long or does not fully match its pattern. */
    InvalidParameter,

    /** HarnessLimits.COMMAND_RUNS commands of this harness are already running. */
    Busy,
}

/**
 * Fixed commands of this script. Declarations are part of the reviewed source: approving the script approves every
 * later run of them — from UI methods (native buttons and WebView `heartbeat.call` alike), timers and events — with
 * no agent turn, review or user confirmation. Desktop only. Programs, parameters and output are never logged.
 */
public interface ScriptCommands {
    /**
     * Declares [name] while the script is evaluated, copying its argument and parameter collections so later
     * mutations cannot change this declaration; later calls throw IllegalStateException. At most
     * HarnessLimits.COMMANDS per activation, each name once. Invalid declarations (blank or parameterized program,
     * undeclared or unused parameters, invalid parameter names, more than HarnessLimits.COMMAND_ARGUMENTS
     * arguments, timeout outside (0, HarnessLimits.COMMAND_TIMEOUT]) throw IllegalArgumentException.
     */
    public fun define(name: ItemName, command: ScriptCommand): ScriptRegistration

    /**
     * Runs declared [name] in the directory of [workspace] (pass ScriptUiCall.workspace for the chat's project)
     * with [parameters], until it exits, times out or the caller is cancelled — cancellation kills the process tree.
     * A UI method waits at most its own budget; start longer commands in script.scope.launch and save the result in
     * data. Requires the published activation and is forbidden from hooks (IllegalStateException).
     */
    public suspend fun run(
        name: ItemName,
        workspace: WorkspaceRef?,
        parameters: Map<String, String> = emptyMap(),
    ): ScriptCommandResult
}
