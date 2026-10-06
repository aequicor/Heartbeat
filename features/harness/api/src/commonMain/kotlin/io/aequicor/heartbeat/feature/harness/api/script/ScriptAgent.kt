package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.harness.api.ItemName
import kotlinx.serialization.json.JsonObject

/** Trusted host context and untrusted model arguments for a script tool; never log either. */
public data class ScriptToolCall(val context: AgentToolContext, val arguments: JsonObject) {
    override fun toString(): String = "ScriptToolCall(***)"
}

/** Private tool output; the host applies normal result limits and hook processing. */
public data class ScriptToolResult(val text: String, val isError: Boolean = false) {
    override fun toString(): String = "ScriptToolResult(***)"
}

/** Registers contributions under this harness's immutable namespace, never arbitrary global tool names. */
public interface ScriptAgent {
    /**
     * Declares hs_<harness slug>_<name>. Declaration is frozen after publication: changing schema requires a new
     * name. Execution rechecks activation, ordinary policy, trust and call lifetime. Command is the safe default.
     * At most HarnessLimits.SCRIPT_TOOLS tools are active; handler waiting is bounded to 60 seconds plus lifetime.
     */
    public fun tool(
        name: ItemName,
        description: String,
        schema: JsonObject,
        action: AgentToolAction = AgentToolAction.Command,
        handler: suspend (ScriptToolCall) -> ScriptToolResult,
    ): ScriptRegistration

    /** Contributes private context within 0.5 seconds; only allowed declared tools may appear in guidance. */
    public fun instructions(handler: suspend (AgentToolScope) -> String): ScriptRegistration
}
