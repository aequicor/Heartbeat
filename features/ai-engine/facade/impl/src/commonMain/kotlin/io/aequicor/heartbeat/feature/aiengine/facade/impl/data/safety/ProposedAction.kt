package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Complete call captured by the gate, independently of optional structural analyzers. Declarations and argument
 * text describe a proposal, not verified effects or user authority. The policy reviews them as untrusted data;
 * the dispatcher retains the execution binding and revalidates the declaration, arguments and resource snapshot.
 */
internal class ProposedAction private constructor(val data: JsonObject) {
    override fun toString(): String = "ProposedAction"

    companion object {
        fun hosted(
            context: AgentToolContext,
            spec: AgentToolSpec,
            arguments: JsonObject,
            approval: AgentToolApproval,
            isAdapterOperated: Boolean,
        ): ProposedAction = capture(
            buildJsonObject {
                put("route", if (isAdapterOperated) "adapter_operated" else "hosted")
                put("tool", spec.name)
                put("description", spec.description)
                put("input_schema", spec.inputSchema)
                put("declared_action", spec.action.name)
                put("arguments", arguments)
                put(
                    "presentation",
                    buildJsonObject {
                        put("title", approval.title)
                        put("description", approval.description)
                    },
                )
                put("workspace", context.workspace?.value)
            },
        )

        fun native(context: AgentToolContext, call: NativeToolCall): ProposedAction = capture(
            buildJsonObject {
                put("route", "native")
                put("tool", call.name)
                put("declared_action", call.action.name)
                put("arguments", call.arguments ?: JsonNull)
                put("command", call.command)
                put("paths", JsonArray(call.paths.map(::JsonPrimitive)))
                put("working_directory", call.workingDirectory)
                put("workspace", context.workspace?.value)
                put(
                    "shell",
                    call.shell?.let { shell ->
                        buildJsonObject {
                            put("dialect", shell.dialect.name)
                            put("executable", shell.executable)
                            put("argument_prefix", JsonArray(shell.argumentPrefix.map(::JsonPrimitive)))
                            put("initialization", shell.initialization)
                        }
                    } ?: JsonNull,
                )
            },
        )

        /** JSON containers may wrap mutable collections; never retain the caller's backing storage. */
        private fun capture(data: JsonObject): ProposedAction =
            ProposedAction(Json.parseToJsonElement(data.toString()).jsonObject)
    }
}
