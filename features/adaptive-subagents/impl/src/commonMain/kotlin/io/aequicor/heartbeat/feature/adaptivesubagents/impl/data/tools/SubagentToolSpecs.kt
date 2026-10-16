package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.tools

import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelTags
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentComplexity
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentNeed
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentTools
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentTools.Arguments
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal const val MAX_TASK = 8_000
internal const val MAX_TITLE = 60

/** Needs by argument value; declared before the specs that list them. */
private val NEEDS = mapOf(
    "images" to SubagentNeed.Images,
    "pdf" to SubagentNeed.Pdf,
    "long_context" to SubagentNeed.LongContext,
)

internal val SpawnSpec = AgentToolSpec(
    SubagentTools.SPAWN,
    "Start a subagent in a new chat for a self-contained subtask. The host chooses its model, connection and " +
        "reasoning effort for the priority and complexity you name. It runs in the background: continue your work " +
        "and read its result with ${SubagentTools.RECEIVE}.",
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            string(Arguments.TASK, "The complete, self-contained task; the subagent sees nothing else")
            string(Arguments.TITLE, "A short title of the subagent's chat")
            enum(Arguments.PRIORITY, "What to optimise for", SubagentPriority.entries.map { it.name.lowercase() })
            enum(
                Arguments.COMPLEXITY,
                "How demanding the task is",
                SubagentComplexity.entries.map { it.name.lowercase() },
            )
            strings(Arguments.NEEDS, "Required inputs", NEEDS.keys.toList())
            strings(Arguments.TAGS, "Kinds of the task", ModelTags.KNOWN)
        }
        putJsonArray("required") { add(JsonPrimitive(Arguments.TASK)) }
    },
    action = AgentToolAction.Command,
)

internal val ReceiveSpec = AgentToolSpec(
    SubagentTools.RECEIVE,
    "Read a bounded batch of complete results of this session; cleanup retries may repeat a result. " +
        "Call again when more results remain unread. With " +
        "${Arguments.WAIT}=true and none finished yet, request a wake when one finishes. " +
        "End your turn only when the result confirms the wake; on refusal keep working and read results later.",
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(Arguments.WAIT) {
                put("type", "boolean")
                put("description", "Sleep until a subagent finishes when none has yet")
            }
        }
    },
)

/** The request of a spawn call, or why its arguments are invalid. */
internal fun JsonObject.spawnRequest(): Result<SubagentRequest> {
    val task = text(Arguments.TASK).orEmpty()
    val priority = text(Arguments.PRIORITY)
    val complexity = text(Arguments.COMPLEXITY)
    return when {
        task.isEmpty() -> Result.failure(IllegalArgumentException("the task is empty"))

        task.length > MAX_TASK -> Result.failure(IllegalArgumentException("the task exceeds $MAX_TASK characters"))

        priority != null && SubagentPriority.entries.none { it.name.equals(priority, true) } ->
            Result.failure(IllegalArgumentException("unknown ${Arguments.PRIORITY}"))

        complexity != null && SubagentComplexity.entries.none { it.name.equals(complexity, true) } ->
            Result.failure(IllegalArgumentException("unknown ${Arguments.COMPLEXITY}"))

        else -> Result.success(
            SubagentRequest(
                task,
                SubagentPriority.entries.firstOrNull { it.name.equals(priority, true) },
                SubagentComplexity.entries.firstOrNull { it.name.equals(complexity, true) }
                    ?: SubagentComplexity.Medium,
                texts(Arguments.NEEDS).mapNotNull(NEEDS::get).toSet(),
                texts(Arguments.TAGS).map(String::lowercase).toSet(),
            ),
        )
    }
}

/** A string argument, or null. */
internal fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull?.trim()

/** A boolean argument, false when absent. */
internal fun JsonObject.flag(name: String): Boolean = (get(name) as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.texts(name: String): List<String> =
    (get(name) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }

private fun JsonObjectBuilder.string(name: String, description: String) = putJsonObject(name) {
    put("type", "string")
    put("description", description)
}

private fun JsonObjectBuilder.enum(name: String, description: String, values: List<String>) = putJsonObject(name) {
    put("type", "string")
    put("description", description)
    putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
}

private fun JsonObjectBuilder.strings(name: String, description: String, values: List<String>) = putJsonObject(name) {
    put("type", "array")
    put("description", description)
    putJsonObject("items") {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }
}
