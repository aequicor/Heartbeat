package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Stable binding for the caller, arguments and relevant page state. */
internal fun operationBinding(
    prefix: String,
    context: AgentToolContext,
    original: AgentToolApproval,
    arguments: JsonObject,
    change: String,
    state: JsonObject? = null,
): String {
    val identity = buildJsonObject {
        put("session", Json.encodeToJsonElement(context.session))
        put("workspace", context.workspace?.value)
        put("turn", context.turn.value)
        put("request", context.request?.value)
        put("call", context.callId?.value)
        put("target", Json.encodeToJsonElement(context.target))
        context.reviewTarget?.let { put("reviewTarget", Json.encodeToJsonElement(it)) }
        put(
            "original",
            Json.encodeToJsonElement(listOf(original.name, original.title, original.description, original.binding)),
        )
        put("arguments", arguments)
        put("change", state?.toString() ?: change)
    }.toString()
    val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).toHexString()
    return "$prefix:$hash"
}
