package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.tools

import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8

/** The approval of [arguments], bound to the trusted caller identity. */
internal fun subagentApproval(context: AgentToolContext, title: String, arguments: JsonObject): AgentToolApproval {
    val identity = buildJsonObject {
        put("session", Json.encodeToJsonElement(context.session))
        put("workspace", context.workspace?.value)
        put("turn", context.turn.value)
        put("request", context.request?.value)
        put("call", context.callId?.value)
        put("target", Json.encodeToJsonElement(context.target))
        context.reviewTarget?.let { put("reviewTarget", Json.encodeToJsonElement(it)) }
        put("arguments", arguments)
    }.toString()
    val binding = "subagent:" + identity.encodeUtf8().sha256().hex()
    return AgentToolApproval(SpawnSpec.name, title, arguments.text(SubagentTools.Arguments.TASK), binding)
}
