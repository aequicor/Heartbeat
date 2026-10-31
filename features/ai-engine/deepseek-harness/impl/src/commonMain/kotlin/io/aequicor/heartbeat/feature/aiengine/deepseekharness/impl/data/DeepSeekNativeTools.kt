package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Native catalog presented to the policy engine; DSH's ACP tool kinds are classified into these groups. */
internal val DeepSeekNativeCatalog = listOf(
    NativeToolSpec("read", AgentToolAction.Read, false, true),
    NativeToolSpec("edit", AgentToolAction.Edit, false, true),
    NativeToolSpec("command", AgentToolAction.Command, false, true),
    NativeToolSpec("other", AgentToolAction.Command, false, true),
)

/**
 * Classifies one observed DSH tool call into a Heartbeat native call. DSH's permission request carries only the
 * `toolCallId`, so the adapter supplies the structured ACP `kind` and `rawInput` (arguments) it recorded from the
 * preceding `tool_call` update. The display title is never used: a misleading title must not relax the trust gate,
 * and an unknown or missing kind conservatively stays a command.
 */
internal fun dshNativeCall(kind: String?, rawInput: JsonObject?, workingDirectory: String): NativeToolCall {
    val name = when (kind) {
        "read" -> "read"
        "edit" -> "edit"
        "execute" -> "command"
        else -> "other"
    }
    val action = when (name) {
        "read" -> AgentToolAction.Read
        "edit" -> AgentToolAction.Edit
        else -> AgentToolAction.Command
    }
    val arguments = rawInput
    val paths = arguments?.let { args ->
        listOfNotNull(
            args["path"]?.jsonPrimitive?.contentOrNull,
            args["file_path"]?.jsonPrimitive?.contentOrNull,
            args["file"]?.jsonPrimitive?.contentOrNull,
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
