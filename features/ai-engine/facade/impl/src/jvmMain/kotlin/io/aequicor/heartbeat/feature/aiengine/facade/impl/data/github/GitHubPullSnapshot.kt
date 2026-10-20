package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.github

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8

internal data class GitHubPullSnapshot(
    val configuration: GitHubPullConfiguration,
    val operation: GitHubPullOperation,
    val context: AgentToolContext,
) {
    init {
        require(context.workspace != null && context.request != null && context.callId != null)
    }

    val evidence = buildJsonObject {
        put("session", Json.encodeToJsonElement(context.session))
        put("workspace", context.workspace?.value)
        put("turn", context.turn.value)
        put("request", context.request?.value)
        put("call", context.callId?.value)
        put("target", Json.encodeToJsonElement(context.target))
        put("reviewTarget", Json.encodeToJsonElement(context.reviewTarget))
        put("trust", context.trust.name)
        put("decisionMode", context.decisionMode.name)
        put("repository", configuration.repository)
        put("account", configuration.account)
        put("operation", operation.name)
        put("number", operation.number)
        put("expected_revision", operation.expected)
        put("payload", operation.payload)
    }.toString()
    val approval = AgentToolApproval(
        operation.name,
        "GitHub: ${operation.name} · ${configuration.repository}",
        "Account: ${configuration.account}\nRepository: ${configuration.repository}\n" +
            "PR: ${operation.number ?: "new draft"}\n\n${operation.payload}",
        "github-pr:" + (configuration.binding + "\u0000" + evidence).encodeUtf8().sha256().hex(),
    )

    override fun toString(): String = "GitHubPullSnapshot(***)"
}
