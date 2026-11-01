package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure

/**
 * Human interaction is independent of a repaired hierarchy. Old Studio records admitted human input, including
 * parentless helpers. Their explicit compatibility provenance preserves that right without inventing an owner.
 * Current full chat roles retain it when a creator disappears. Unknown metadata and executor roles grant nothing.
 * Callers use canonical identity resolution first, so duplicate ids cannot authorize a mutation.
 */
internal val StudioChatRecord.isHumanConversation: Boolean
    get() {
        if (identityIssue == "DuplicateIdentity") return false
        val saved = identity ?: return true
        if (saved.version != 1) return false
        if (saved.origin !in HUMAN_ORIGINS) return false
        val context = saved.context
        if (context != null) return context.id.value == id && context.isHumanInteractive
        return saved.origin == StudioIdentityOrigin.LegacyInteractiveHelper && !saved.hasContextPayload
    }

/** Gate for direct user input, configuration and metadata commands; observing and stopping use separate paths. */
internal fun StudioChatRecord.requireHumanConversation(): StudioChatRecord {
    if (!isHumanConversation) throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
    return this
}

private val HUMAN_ORIGINS = setOf(
    StudioIdentityOrigin.UserConversation,
    StudioIdentityOrigin.AgentCreated,
    StudioIdentityOrigin.LegacyInteractiveHelper,
)
