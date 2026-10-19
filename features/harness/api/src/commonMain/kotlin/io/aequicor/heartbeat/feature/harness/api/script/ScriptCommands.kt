package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessCommandReceipt
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A program with arguments fixed in the approved source. An argument that is exactly `{name}` is replaced by the
 * value of parameter `name`, which must fully match its pattern in [parameters]; nothing else is substituted and the
 * words are not expanded or split. Windows resolves [program] only to an external application or `.bat`/`.cmd`,
 * never a PowerShell cmdlet, function or `.ps1` script; wildcard or backtick syntax in [program] is refused.
 * Empty words, `cmd.exe` specials (`& | < > ^ % ! ( ) "`, line breaks), and words ending in `\` that also contain
 * whitespace or `, ; =` are refused as invalid on Windows. Other arguments containing `, ; =` stay one argument.
 * A non-null [requiresDecision] disables ordinary run and requires a distinct host-owned process decision; approval
 * of this source never approves later merges or releases. The label alone does not prove command semantics.
 * A relative [program] runs from the project directory (`./gradlew`, `.\gradlew.bat`). Pass the trusted
 * `ScriptUiCall.workspace`, never a project built from input.
 */
public data class ScriptCommand(
    val program: String,
    val arguments: List<String> = emptyList(),
    val parameters: Map<String, Regex> = emptyMap(),
    val timeout: Duration = 1.minutes,
    val requiresDecision: ProcessAuthority? = null,
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
@Serializable
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

    /** This declaration requires an exact host-owned process decision; use runForProcess. */
    DecisionRequired,
}

/**
 * Fixed commands of this script. Declarations are part of the reviewed source: approving the script approves every
 * later run of them — from UI methods (native buttons and WebView `heartbeat.call` alike), timers and events — with
 * no agent turn, review or user confirmation for unguarded declarations. A declaration with requiresDecision instead
 * requires [runForProcess] and a separate host decision. Desktop only. Programs, parameters and output are never
 * logged.
 */
public interface ScriptCommands {
    /**
     * Requests or executes one guarded command for this exact process attempt. A Pending reply is durable and
     * returns immediately; park the process and retry after the human decides in the native harness screen.
     * Replays reuse the same decision. Denied, revoked, expired, already dispatched and completed snapshots never
     * execute again. The declaration must specify ScriptCommand.requiresDecision; ordinary run refuses it.
     * The host binds expanded words, timeout, script revision, project, repository, input revision and full HEAD SHA.
     * Consent covers these words only; the authority label does not prove what a program does. The source reviewer
     * must require conditional external writes bound to the shown SHA. No answer method is exposed to scripts.
     */
    public suspend fun runForProcess(
        name: ItemName,
        workspace: WorkspaceRef?,
        process: ProcessWorkflowKey,
        parameters: Map<String, String> = emptyMap(),
    ): ScriptProcessCommandResult = ScriptProcessCommandResult.Unavailable

    /**
     * Reads a previously issued decision without requesting approval or executing any command, including Git.
     * Save the decision id returned by runForProcess before waiting. The current approved declaration, harness
     * revision, physical workspace, authority and exact process key must still match the original request.
     * Changing HEAD does not hide a saved result. Recorded contains no output and proves only the native outcome;
     * Completed without Recorded is a legacy result whose details are unknown. Executing is never retried here.
     */
    public suspend fun resultForProcess(
        name: ItemName,
        workspace: WorkspaceRef?,
        process: ProcessWorkflowKey,
        decisionId: String,
        parameters: Map<String, String> = emptyMap(),
    ): ScriptProcessCommandResult = ScriptProcessCommandResult.Unavailable

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

/** Host-mediated execution is independent of the script's self-declared ProcessAuthority grants. */
public sealed interface ScriptProcessCommandResult {
    /** Observe or await this durable record; only the native host UI can answer it. */
    public data class Decision(val id: String, val phase: ProcessDecisionPhase) : ScriptProcessCommandResult

    /** The one claimed execution returned. Later calls read Recorded, or Executing after an uncertain timeout. */
    public data class Finished(val result: ScriptCommandResult) : ScriptProcessCommandResult

    /** A saved native result of this exact command; no execution occurred and no stdout or stderr is retained. */
    public data class Recorded(val receipt: ProcessCommandReceipt) : ScriptProcessCommandResult

    /** Admission failed or the journal could not be verified. This is never permission to run unguarded. */
    public data object Unavailable : ScriptProcessCommandResult
}
