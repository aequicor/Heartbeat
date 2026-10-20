package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.browser.impl.domain.reviewedBrowserState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One exact proposal for the embedded browser: the approval bound to it, the model's [arguments] and the page
 * [evidence] (tab, document, target, focus) that must still match immediately before execution. [change] is what the
 * confirmation describes; the binding stays on the evidence's stable part, see [reviewedBrowserState].
 */
internal data class BrowserOperationRequest(
    val approval: AgentToolApproval,
    val arguments: JsonObject,
    val evidence: JsonObject?,
    val change: String,
) {
    override fun toString(): String = "BrowserOperationRequest(***)"
}

/**
 * The model supplies only [arguments]; the caller identity, the page evidence and the approval binding come from the
 * host. [evidence] is null for an action that depends on no page, such as opening a private address in a new tab.
 */
internal fun browserOperationRequest(
    context: AgentToolContext,
    original: AgentToolApproval,
    arguments: JsonObject,
    evidence: JsonObject?,
): BrowserOperationRequest {
    val change = buildJsonObject {
        put("operation", original.name)
        put("arguments", arguments)
        evidence?.let { put("page", it) }
    }.toString()
    val state = evidence?.let(::reviewedBrowserState) ?: JsonObject(emptyMap())
    val binding = operationBinding("browser-reviewed", context, original, arguments, change, state)
    return BrowserOperationRequest(original.copy(binding = binding), arguments, evidence, change)
}
