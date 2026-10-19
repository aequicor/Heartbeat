package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessCommandSnapshot
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommand
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommandRefusal
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommandResult
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommands
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcessCommandResult
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCreationScope
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.HarnessProcessCommandHost
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessCommandInvocation
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandOutcome
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandRequest
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommands
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource

/** Concurrent command runs per harness, shared by all its activations. */
internal interface HarnessCommandRuns {
    /** Takes a slot of [harness] unless [HarnessLimits.COMMAND_RUNS] are taken. */
    fun tryAcquire(harness: HarnessId): Boolean

    /** Returns a slot taken by [tryAcquire]. */
    fun release(harness: HarnessId)
}

/** Host services shared by every activation's [HarnessScriptCommands]. */
internal data class HarnessCommandHost(
    val commands: LocalCommands,
    val scopes: HarnessCreationScope,
    val slots: HarnessCommandRuns,
    val origins: HarnessCallOrigins,
    val processes: HarnessProcessCommandHost? = null,
    val isEnabled: suspend () -> Boolean,
)

/**
 * Commands declared by one approved script activation. Their collections are copied at registration and
 * declarations are frozen at publication, so every program and
 * argument comes from the reviewed source; parameters only fill whole arguments after a full pattern match. A run
 * rechecks the published activation, the toggle and the harness's project scope, and shares
 * [HarnessLimits.COMMAND_RUNS] concurrent runs per harness. Programs, parameters and output are never logged.
 */
internal class HarnessScriptCommands(
    private val request: HarnessActivationRequest,
    private val access: HarnessInstanceAccess,
    private val host: HarnessCommandHost,
) : ScriptCommands {
    private val log = Log.tag("HarnessScriptCommands")
    private val state = MutableStateFlow(Declarations())

    override fun define(name: ItemName, command: ScriptCommand): ScriptRegistration {
        val snapshot = command.copy(arguments = command.arguments.toList(), parameters = command.parameters.toMap())
        snapshot.validate()
        while (true) {
            val previous = state.value
            check(!previous.isSealed && access.isRegistrationAllowed && !access.isActive) {
                "Commands are declared only while the script is evaluated"
            }
            check(name !in previous.commands) { "Command is already declared" }
            check(previous.commands.size < HarnessLimits.COMMANDS) { "Too many commands" }
            if (state.compareAndSet(previous, previous.copy(commands = previous.commands + (name to snapshot)))) break
        }
        log.v { "state: a script command declared" }
        return ScriptRegistration {
            state.update { it.copy(commands = it.commands - name) }
            log.v { "state: a script command disposed" }
        }
    }

    /** Freezes declarations at publication; later declarations fail, disposal still removes them. */
    fun seal() {
        state.update { it.copy(isSealed = true) }
        log.v { "state: script commands sealed" }
    }

    /** Revokes every declaration with the activation. */
    fun close() {
        state.value = Declarations(isSealed = true)
        log.v { "state: script commands revoked" }
    }

    override suspend fun run(
        name: ItemName,
        workspace: WorkspaceRef?,
        parameters: Map<String, String>,
    ): ScriptCommandResult {
        check(!host.origins.current().isHookRestricted) { "Hooks cannot run commands" }
        check(access.isActive) { "Commands require the published activation" }
        val started = TimeSource.Monotonic.markNow()
        val result = admitted(name, workspace, parameters)
        log.i {
            "harness ${request.harness.id.value} command ended: ${result.summary()} in ${started.elapsedNow()}"
        }
        return result
    }

    override suspend fun runForProcess(
        name: ItemName,
        workspace: WorkspaceRef?,
        process: ProcessWorkflowKey,
        parameters: Map<String, String>,
    ): ScriptProcessCommandResult {
        check(!host.origins.current().isHookRestricted) { "Hooks cannot run commands" }
        check(access.isActive) { "Commands require the published activation" }
        val call = processInvocation(name, workspace, process, parameters)
            ?: return ScriptProcessCommandResult.Unavailable
        val executor = host.processes
        val isAvailable = executor != null && host.isEnabled() && host.commands.isAvailable
        val isAdmitted = isAvailable && scopeRefusal(call.workspace) == null
        return if (!isAdmitted || !host.slots.tryAcquire(request.harness.id)) {
            ScriptProcessCommandResult.Unavailable
        } else {
            try {
                executor.execute(call)
            } finally {
                host.slots.release(request.harness.id)
            }
        }
    }

    override suspend fun resultForProcess(
        name: ItemName,
        workspace: WorkspaceRef?,
        process: ProcessWorkflowKey,
        decisionId: String,
        parameters: Map<String, String>,
    ): ScriptProcessCommandResult {
        check(!host.origins.current().isHookRestricted) { "Hooks cannot read command decisions" }
        check(access.isActive) { "Commands require the published activation" }
        val call = processInvocation(name, workspace, process, parameters)
            ?: return ScriptProcessCommandResult.Unavailable
        val executor = host.processes
        val isAdmitted = executor != null && host.isEnabled() && scopeRefusal(call.workspace) == null
        return if (isAdmitted) {
            check(access.isActive) { "Command decision activation expired" }
            executor.read(call, decisionId)
        } else {
            ScriptProcessCommandResult.Unavailable
        }
    }

    private fun processInvocation(
        name: ItemName,
        workspace: WorkspaceRef?,
        process: ProcessWorkflowKey,
        parameters: Map<String, String>,
    ): ProcessCommandInvocation? {
        val command = state.value.commands[name] ?: return null
        val arguments = command.arguments(parameters.toMap())
        val authority = command.requiresDecision
        return if (arguments == null || authority == null || workspace == null) {
            null
        } else {
            ProcessCommandInvocation(
                request,
                access,
                process,
                workspace,
                authority,
                ProcessCommandSnapshot(command.program, arguments, command.timeout.inWholeMilliseconds),
            )
        }
    }

    private suspend fun admitted(
        name: ItemName,
        workspace: WorkspaceRef?,
        parameters: Map<String, String>,
    ): ScriptCommandResult {
        val command = state.value.commands[name]
        val arguments = command?.arguments(parameters)
        val refusal = when {
            !host.isEnabled() -> ScriptCommandRefusal.Disabled
            !host.commands.isAvailable -> ScriptCommandRefusal.Unsupported
            command == null -> ScriptCommandRefusal.NotDefined
            command.requiresDecision != null -> ScriptCommandRefusal.DecisionRequired
            arguments == null -> ScriptCommandRefusal.InvalidParameter
            workspace == null -> ScriptCommandRefusal.NoProject
            else -> scopeRefusal(workspace)
        }
        if (refusal != null) return ScriptCommandResult.Refused(refusal)
        checkNotNull(command)
        checkNotNull(arguments)
        checkNotNull(workspace)
        if (!host.slots.tryAcquire(request.harness.id)) return ScriptCommandResult.Refused(ScriptCommandRefusal.Busy)
        return try {
            check(access.isActive) { "Commands require the published activation" }
            host.commands.run(LocalCommandRequest(workspace, command.program, arguments, command.timeout)).result()
        } finally {
            host.slots.release(request.harness.id)
        }
    }

    private suspend fun scopeRefusal(workspace: WorkspaceRef): ScriptCommandRefusal? {
        val projects = (host.scopes.resolve(workspace) as? HarnessScope.Projects)?.projects
            ?: return ScriptCommandRefusal.NoProject
        val allowed = (request.harness.scope as? HarnessScope.Projects)?.projects ?: return null
        return ScriptCommandRefusal.OutsideScope.takeUnless { allowed.containsAll(projects) }
    }
}

private data class Declarations(val isSealed: Boolean = false, val commands: Map<ItemName, ScriptCommand> = emptyMap())

private val PARAMETER_NAME = Regex("[a-z][a-z0-9_]{0,31}")

private fun placeholder(argument: String): String? =
    argument.takeIf { it.length > 2 && it.first() == '{' && it.last() == '}' }?.substring(1, argument.length - 1)

/** Checks a declaration before it can be stored; the reviewer and the host see the same fixed words. */
private fun ScriptCommand.validate() {
    require(program.isNotBlank() && '\u0000' !in program) { "A command needs a program" }
    require(placeholder(program) !in parameters) { "Parameters cannot choose the program" }
    require(arguments.size <= HarnessLimits.COMMAND_ARGUMENTS) { "Too many command arguments" }
    require(arguments.none { '\u0000' in it }) { "Command arguments cannot contain NUL" }
    require(timeout.isPositive() && timeout <= HarnessLimits.COMMAND_TIMEOUT) { "Unsupported command timeout" }
    require(parameters.keys.all { PARAMETER_NAME.matches(it) }) { "Invalid command parameter name" }
    val used = arguments.mapNotNull { placeholder(it)?.takeIf { name -> name in parameters } }.toSet()
    require(used == parameters.keys) { "Every command parameter must fill a whole argument" }
    require(
        arguments.filter { placeholder(it) !in parameters }.none { argument ->
            parameters.keys.any { "{$it}" in argument }
        },
    ) { "Parameters fill whole arguments only" }
}

/** Fixed words with whole-argument parameters, or null when the values do not match the declaration exactly. */
private fun ScriptCommand.arguments(values: Map<String, String>): List<String>? {
    if (values.keys != parameters.keys) return null
    val isValid = values.all { (name, value) ->
        value.length <= HarnessLimits.COMMAND_PARAMETER_CHARS && '\u0000' !in value &&
            parameters.getValue(name).matches(value)
    }
    return if (isValid) arguments.map { argument -> placeholder(argument)?.let(values::get) ?: argument } else null
}

internal fun LocalCommandOutcome.result(): ScriptCommandResult = when (this) {
    is LocalCommandOutcome.Exited -> ScriptCommandResult.Exited(exitCode, output)
    is LocalCommandOutcome.TimedOut -> ScriptCommandResult.TimedOut(output)
    LocalCommandOutcome.NoProject -> ScriptCommandResult.Refused(ScriptCommandRefusal.NoProject)
    LocalCommandOutcome.Invalid -> ScriptCommandResult.Refused(ScriptCommandRefusal.InvalidParameter)
    LocalCommandOutcome.Unsupported -> ScriptCommandResult.Refused(ScriptCommandRefusal.Unsupported)
}

private fun ScriptCommandResult.summary(): String = when (this) {
    is ScriptCommandResult.Exited -> "exit $exitCode"
    is ScriptCommandResult.TimedOut -> "timeout"
    is ScriptCommandResult.Refused -> "refused ${reason.name}"
}
