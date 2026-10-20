package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One exact proposal and the backend evidence that must still match immediately before execution. */
internal data class ExternalBrowserOperationRequest(
    val approval: AgentToolApproval,
    val arguments: JsonObject,
    val snapshot: JsonObject,
    val change: String,
) {
    override fun toString(): String = "ExternalBrowserOperationRequest(***)"
}

/** The model cannot supply the identity, page evidence or approval binding. */
internal fun externalBrowserOperationRequest(
    context: AgentToolContext,
    original: AgentToolApproval,
    arguments: JsonObject,
    effectiveArguments: JsonObject,
    snapshot: JsonObject,
): ExternalBrowserOperationRequest {
    val change = buildJsonObject {
        put("operation", original.name)
        put("arguments", effectiveArguments)
        put("page", snapshot)
    }.toString()
    val binding =
        operationBinding("external-reviewed", context, original, arguments, change)
    return ExternalBrowserOperationRequest(original.copy(binding = binding), effectiveArguments, snapshot, change)
}
