package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal val KimiNativeCatalog = listOf(
    NativeToolSpec("read", AgentToolAction.Read, false, true),
    NativeToolSpec("edit", AgentToolAction.Edit, false, true),
    NativeToolSpec("execute", AgentToolAction.Command, false, true),
    NativeToolSpec("other", AgentToolAction.Command, false, true),
)

internal fun kimiNativeCall(toolCall: JsonObject, workingDirectory: String): NativeToolCall {
    val kind = toolCall["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val fallbackName = toolCall["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val name = when {
        kind == "read" || fallbackName == "read" -> "read"
        kind == "edit" || fallbackName == "edit" -> "edit"
        kind == "execute" || fallbackName == "execute" -> "execute"
        else -> "other"
    }
    val action = when (name) {
        "read" -> AgentToolAction.Read
        "edit" -> AgentToolAction.Edit
        else -> AgentToolAction.Command
    }
    val arguments = toolCall["rawInput"] as? JsonObject
    val paths = arguments?.let { args ->
        listOfNotNull(
            args["path"]?.jsonPrimitive?.contentOrNull,
            args["file_path"]?.jsonPrimitive?.contentOrNull,
        )
    }.orEmpty()
    return NativeToolCall(
        name,
        action,
        paths = paths,
        command = arguments?.get("command")?.jsonPrimitive?.contentOrNull,
        arguments = arguments,
        workingDirectory = workingDirectory,
    )
}
