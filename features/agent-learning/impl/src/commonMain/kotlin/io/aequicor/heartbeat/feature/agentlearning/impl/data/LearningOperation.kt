package io.aequicor.heartbeat.feature.agentlearning.impl.data

import io.aequicor.heartbeat.feature.agentlearning.api.LearningTools
import io.aequicor.heartbeat.feature.agentlearning.api.ModelScope
import io.aequicor.heartbeat.feature.agentlearning.impl.domain.RememberDraft
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8

/** Complete operation snapshot and its opaque host binding; never include lesson text in diagnostics. */
internal data class LearningOperation(val approval: AgentToolApproval, val change: String) {
    override fun toString(): String = "LearningOperation(***)"
}

/** Binds the full stored instruction and the host-resolved project and model scope. */
internal fun learningOperation(
    context: AgentToolContext,
    draft: RememberDraft,
    project: WorkspaceRef?,
    modelScope: ModelScope?,
    original: AgentToolApproval,
): LearningOperation {
    val change = buildJsonObject {
        put("operation", LearningTools.REMEMBER)
        put("kind", draft.kind.name)
        put("title", draft.title)
        put("content", draft.content)
        put("description", draft.description)
        put("reason", draft.reason)
        put("agentRatedSafe", draft.isSafe)
        put("project", Json.encodeToJsonElement(project))
        put("modelScope", Json.encodeToJsonElement(modelScope))
    }.toString()
    val identity = buildJsonObject {
        put("session", Json.encodeToJsonElement(context.session))
        put("workspace", Json.encodeToJsonElement(context.workspace))
        put("turn", context.turn.value)
        put("request", context.request?.value)
        put("call", context.callId?.value)
        put("target", Json.encodeToJsonElement(context.target))
        context.reviewTarget?.let { put("reviewTarget", Json.encodeToJsonElement(it)) }
        put("decisionMode", context.decisionMode.name)
        put("approvalPolicy", original.binding)
        put("change", change)
    }.toString()
    val approval = original.copy(binding = "learning-reviewed:" + identity.encodeUtf8().sha256().hex())
    return LearningOperation(approval, change)
}
