package io.aequicor.heartbeat.feature.planningmode.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import io.aequicor.heartbeat.feature.planningmode.api.PlanningTools
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import io.aequicor.heartbeat.feature.planningmode.api.ShellKind
import io.aequicor.heartbeat.feature.planningmode.api.isReadOnlyCommand
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps a planning chat read-only. For a session bound to a plan in [PlanMode.Planning]: reads and the planning
 * tools continue; edits are refused; shell commands continue only when [isReadOnlyCommand] proves they read, other
 * commands require a decision (a dialog in interactive studio modes, a refusal in automatic mode); every other
 * command-like tool (subagents, background actions, browser input) is refused. Until plans are loaded the hook
 * cannot tell planning sessions apart, so edits and commands of every session require a decision. Arguments are
 * untrusted and never logged; the facade logs the verdict centrally.
 */
internal class PlanningReadOnlyHook(private val view: PlanningView, private val isEnabled: () -> Boolean) :
    SessionHook {
    private val log = Log.tag("PlanningReadOnlyHook")

    override val isIntercepting: Boolean get() = isEnabled()

    @HighFrequency
    override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict {
        if (!isEnabled() || call.action == AgentToolAction.Read) return ToolHookVerdict.Continue
        val state = view.state.value
        if (state !is PlanningState.Ready) {
            log.v { "plans are not loaded; tool needs a decision" }
            return ToolHookVerdict.Ask(LOADING_REASON)
        }
        if (state.planOf(call.context.session)?.mode != PlanMode.Planning) return ToolHookVerdict.Continue
        val name = hostedName(call.name)
        return when {
            name in PlanningTools.ALL || name in WEB_READS -> ToolHookVerdict.Continue
            call.action == AgentToolAction.Edit -> ToolHookVerdict.Deny(EDIT_REASON)
            else -> commandVerdict(name, call)
        }
    }

    private fun commandVerdict(name: String, call: HookedToolCall): ToolHookVerdict {
        val command = (call.arguments["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (name !in SHELL_TOOLS || command == null) return ToolHookVerdict.Deny(COMMAND_REASON)
        val shell = if (name.contains("powershell", ignoreCase = true)) ShellKind.PowerShell else ShellKind.Posix
        return if (isReadOnlyCommand(command, shell)) ToolHookVerdict.Continue else ToolHookVerdict.Ask(SHELL_REASON)
    }

    private companion object {
        /** Native shells of Claude and Pi and the coding command of Koog. */
        val SHELL_TOOLS = setOf("Bash", "bash", "run_command", "powershell", "PowerShell")

        /** Command-classified tools that only fetch public pages. */
        val WEB_READS = setOf("WebFetch")

        const val EDIT_REASON = "Planning mode is read-only: record the plan with ${PlanningTools.WRITE}; " +
            "changes to the workspace start once the user implements the plan."
        const val COMMAND_REASON = "Planning mode is read-only: this tool may change something. Read files and " +
            "record the plan with ${PlanningTools.WRITE} instead."
        const val SHELL_REASON = "Planning mode is read-only: allow this command only if it changes nothing."
        const val LOADING_REASON = "Planning mode state is loading: allow this action only if it changes nothing."
    }
}

/** A hosted tool name without the MCP prefix that engines add (`mcp__heartbeat__plan_write`). */
internal fun hostedName(name: String): String = if (name.startsWith("mcp__")) name.substringAfterLast("__") else name
