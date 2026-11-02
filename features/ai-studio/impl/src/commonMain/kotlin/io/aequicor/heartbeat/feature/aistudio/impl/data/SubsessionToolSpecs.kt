package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationLimits
import io.aequicor.heartbeat.feature.aistudio.api.SubsessionTools
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal val SUBSESSION_START_SPECS = listOf(
    AgentToolSpec(
        SubsessionTools.SPAWN,
        "Create a full interactive subsession under your family root. The user can work in it and change its model. " +
            "An optional message is queued without waiting for completion; a launch receipt is not a result. " +
            "Inherits your current model and workspace. Use subagent_spawn for an owner-only task executor.",
        subsessionSchema(listOf("title")) {
            string("title", "Short visible chat title", HostConversationLimits.MAX_TITLE)
            string("message", "Optional initial creator message", HostConversationLimits.MAX_MESSAGE)
        },
        AgentToolAction.Command,
    ),
    AgentToolSpec(
        SubsessionTools.SEND,
        "Persist a creator message in your direct subsession's shared FIFO, including while it is busy. " +
            "Captures its current configuration; preserves human instruction priority. " +
            "Paused queues stay paused; disabled queues refuse before acknowledgement. " +
            "A repeated request never replays an accepted send. Returns a request id, not completion.",
        subsessionSchema(listOf("session_id", "message")) {
            session()
            string("message", "Complete creator message", HostConversationLimits.MAX_MESSAGE)
        },
        AgentToolAction.Command,
    ),
)

internal val SUBSESSION_RECOVERY_SPECS = listOf(
    AgentToolSpec(
        SubsessionTools.STATUS,
        "Observe a subsession's logical identity and current attempt. Unknown or missing evidence is not completion.",
        subsessionSchema(listOf("session_id")) { session() },
    ),
    AgentToolSpec(
        SubsessionTools.RESULT,
        "Read the confirmed result of exactly request_id. A null result proves neither completion nor non-submission.",
        subsessionSchema(listOf("session_id", "request_id")) {
            session()
            request()
        },
    ),
    AgentToolSpec(
        SubsessionTools.WAIT,
        "Explicitly wait by bounded polling for exactly request_id. " +
            "Timeout does not stop the child or prove completion. " +
            "This call blocks your thinking while waiting; spawn and send themselves are nonblocking.",
        subsessionSchema(listOf("session_id", "request_id")) {
            session()
            request()
            putJsonObject("timeout_ms") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", SUBSESSION_MAX_WAIT_MS)
                put("default", SUBSESSION_DEFAULT_WAIT_MS)
            }
        },
    ),
    AgentToolSpec(
        SubsessionTools.CANCEL,
        "Stop exactly request_id of your direct subsession. Unconfirmed means it may still run. " +
            "Does not close the chat; an older cancel never targets a newer turn.",
        subsessionSchema(listOf("session_id", "request_id")) {
            session()
            request()
        },
        AgentToolAction.Command,
    ),
)

private fun subsessionSchema(required: List<String>, properties: JsonObjectBuilder.() -> Unit): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties", properties)
        putJsonArray("required") { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    }

private const val MAX_SUBSESSION_ID_CHARS = 128
private fun JsonObjectBuilder.session() =
    string("session_id", "Stable logical id returned by subsession_spawn", MAX_SUBSESSION_ID_CHARS)
private fun JsonObjectBuilder.request() =
    string("request_id", "Exact request id returned by spawn or send", MAX_SUBSESSION_ID_CHARS)
private fun JsonObjectBuilder.string(name: String, description: String, maximum: Int) = putJsonObject(name) {
    put("type", "string")
    put("description", description)
    put("minLength", 1)
    put("maxLength", maximum)
}

internal const val SUBSESSION_MAX_WAIT_MS: Long = 30_000
internal const val SUBSESSION_DEFAULT_WAIT_MS: Long = 1_000

@Serializable
internal data class SubsessionSpawnArguments(val title: String, val message: String? = null)

@Serializable
internal data class SubsessionSendArguments(
    @SerialName("session_id") val sessionId: String,
    val message: String,
)

@Serializable
internal data class SubsessionStatusArguments(
    @SerialName("session_id") val sessionId: String,
)

@Serializable
internal data class SubsessionRequestArguments(
    @SerialName("session_id") val sessionId: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("timeout_ms") val timeoutMs: Long = SUBSESSION_DEFAULT_WAIT_MS,
)
