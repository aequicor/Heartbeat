package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentResourcePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8

/** An immutable proposal, tied to its exact host identity and content; never include it in diagnostics. */
internal data class UiToolOperation(
    val approval: AgentToolApproval,
    val original: AgentToolApproval,
    val change: String,
    val methodBinding: String? = null,
    val libraryRevision: Long? = null,
) {
    override fun toString(): String = "UiToolOperation(***)"
}

/** Binds caller, provider/model, arguments, previous state and executable source without trusting model tokens. */
internal fun uiToolOperation(
    context: AgentToolContext,
    original: AgentToolApproval,
    arguments: JsonObject,
    snapshot: JsonObject,
    methodBinding: String? = null,
    libraryRevision: Long? = null,
    resourcePolicy: BrowserDocumentResourcePolicy = BrowserDocumentResourcePolicy.Isolated,
): UiToolOperation {
    val change = buildJsonObject {
        put("operation", original.name)
        put("resourcePolicy", resourcePolicy.name)
        put("arguments", arguments)
        put("snapshot", snapshot)
    }.toString()
    val identity = buildJsonObject {
        put("session", Json.encodeToJsonElement(context.session))
        put("workspace", context.workspace?.value)
        put("turn", context.turn.value)
        put("request", context.request?.value)
        put("call", context.callId?.value)
        put("target", Json.encodeToJsonElement(context.target))
        context.reviewTarget?.let { put("reviewTarget", Json.encodeToJsonElement(it)) }
        put("original", original.binding)
        put("method", methodBinding)
        put("libraryRevision", libraryRevision)
        put("change", change)
    }.toString()
    val binding = "ui-operation:" + identity.encodeUtf8().sha256().hex()
    return UiToolOperation(original.copy(binding = binding), original, change, methodBinding, libraryRevision)
}
