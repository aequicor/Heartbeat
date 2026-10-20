package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8

/** Immutable operation input and its pure authorization fingerprint; neither contains a verdict. */
internal data class HarnessOperationSnapshot(val approval: AgentToolApproval, val key: String, val change: String) {
    override fun toString(): String = "HarnessOperationSnapshot(***)"
}

/** Recomputes the exact binding during final stale checks. */
internal fun harnessOperationSnapshot(
    context: AgentToolContext,
    approval: AgentToolApproval,
    change: String,
): HarnessOperationSnapshot {
    val snapshot = buildJsonObject {
        put("caller", Json.encodeToJsonElement(context.session))
        put("workspace", Json.encodeToJsonElement(context.workspace))
        put("turn", context.turn.value)
        put("request", context.request?.value)
        put("call", context.callId?.value)
        put("target", Json.encodeToJsonElement(context.target))
        context.reviewTarget?.let { put("reviewTarget", Json.encodeToJsonElement(it)) }
        put("trust", context.trust.name)
        put("operation", approval.name)
        put("binding", approval.binding)
        put("change", change)
    }.toString()
    val key = snapshot.encodeUtf8().sha256().hex()
    return HarnessOperationSnapshot(approval.copy(binding = "operation=$key"), key, snapshot)
}
