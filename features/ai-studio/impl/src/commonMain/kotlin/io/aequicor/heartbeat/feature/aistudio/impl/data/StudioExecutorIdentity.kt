package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperConversationRole

/** The producer declares interaction intent; only canonical full-chat metadata proves a logical controller. */
internal fun helperConversationIdentity(
    id: String,
    parent: StudioChatRecord?,
    role: HelperConversationRole,
): StudioSessionIdentity {
    check(role != HelperConversationRole.FullConversation || parent == null || parent.isHumanConversation) {
        "An executor or unresolved caller cannot create an interactive conversation"
    }
    val controller = parent?.logicalSession?.takeIf { it.isHumanInteractive }
    return when (role) {
        HelperConversationRole.FullConversation -> if (controller == null) {
            StudioSessionIdentity(StudioIdentityOrigin.LegacyInteractiveHelper)
        } else {
            StudioSessionIdentity(StudioIdentityOrigin.AgentCreated, controller.subsession(LogicalSessionId(id)))
        }

        HelperConversationRole.Executor -> if (controller == null) {
            StudioSessionIdentity(StudioIdentityOrigin.InternalExecution)
        } else {
            StudioSessionIdentity(StudioIdentityOrigin.ExplicitSubagent, controller.subagent(LogicalSessionId(id)))
        }
    }
}
