package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Native catalog presented to the policy engine; DSH's own tool names are classified into these groups. */
internal val DeepSeekNativeCatalog = listOf(
    NativeToolSpec("read", AgentToolAction.Read, false, true),
    NativeToolSpec("edit", AgentToolAction.Edit, false, true),
    NativeToolSpec("command", AgentToolAction.Command, false, true),
    NativeToolSpec("other", AgentToolAction.Command, false, true),
)

/** Tool names DSH reports as read-only; everything else is treated as a command for the trust gate. */
private val READ_TOOLS = setOf(
    "read", "list", "glob", "grep", "search", "web_search", "web_fetch", "view", "cat", "todo_read",
    "todo_list", "skill_list", "context_status", "context_recall", "get_graph", "list_graphs", "get_learned_skill",
)

/** Tool names DSH reports as file edits; writes outside the workspace remain commands. */
private val EDIT_TOOLS = setOf(
    "write", "edit", "multi_edit", "apply_patch", "patch", "replace", "update_file", "create_file",
)

/**
 * Classifies one observed DSH tool call into a Heartbeat native call. DSH's permission request carries only the
 * `toolCallId`, so the adapter supplies the observed `title` (tool name) and `rawInput` (arguments) it recorded
 * from the preceding `tool_call` update.
 */
internal fun dshNativeCall(title: String, rawInput: JsonObject?, workingDirectory: String): NativeToolCall {
    val name = title.substringBefore(' ').takeIf(String::isNotBlank) ?: "other"
    val action = when {
        name in READ_TOOLS -> AgentToolAction.Read
        name in EDIT_TOOLS -> AgentToolAction.Edit
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
