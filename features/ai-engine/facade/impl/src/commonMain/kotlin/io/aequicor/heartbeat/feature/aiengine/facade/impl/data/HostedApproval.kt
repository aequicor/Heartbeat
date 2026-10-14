package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import kotlinx.serialization.json.JsonObject

/** One captured operation; no argument, review reason or presentation text is logged. */
internal data class HostedApproval(
    val owner: AgentToolContribution,
    val spec: AgentToolSpec,
    val arguments: JsonObject,
    val approval: AgentToolApproval,
) {
    override fun toString(): String = "HostedApproval"
}

internal fun AgentToolAutomaticAuthorization.refusal(approval: AgentToolApproval, hook: ToolHookVerdict): String? {
    hook.automaticRefusal()?.let { return it }
    return when (this) {
        is AgentToolAutomaticAuthorization.Denied ->
            reason.ifBlank { "Automatic safety review refused the operation" }.take(SAFETY_NOTE_CHARS)

        is AgentToolAutomaticAuthorization.Allowed ->
            if (this.approval.binding.isNullOrBlank() || this.approval != approval) {
                "Automatic safety authorization does not match this operation"
            } else {
                null
            }
    }
}

/** An Ask hook is a constraint, not permission to open a dialog from a noninteractive operation. */
internal fun ToolHookVerdict.automaticRefusal(): String? = when (this) {
    ToolHookVerdict.Continue -> null

    is ToolHookVerdict.Deny -> "Blocked by a session hook: ${reason.take(SAFETY_NOTE_CHARS)}"

    is ToolHookVerdict.Ask ->
        "Automatic safety authorization cannot satisfy a session hook: " +
            reason.take(SAFETY_NOTE_CHARS)
}

private const val SAFETY_NOTE_CHARS = 2000
