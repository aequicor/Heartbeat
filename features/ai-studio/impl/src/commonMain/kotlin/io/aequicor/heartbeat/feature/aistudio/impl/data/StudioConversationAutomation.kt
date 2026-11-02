package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.serialization.Serializable

/** Exact conversation source of an automatic action; a role alone never grants work continuation authority. */
@Serializable
internal data class StudioConversationSource(
    val chat: String,
    val request: RequestId,
    val revision: Long,
    val causes: Set<RequestInitiator>,
    val actionFences: Set<RequestId> = emptySet(),
)

/** Independent human commands use role gates; automatic follow-ups additionally require durable request provenance. */
@Inject
internal class StudioConversationAutomation(private val attempts: StudioHelperAttempts) {
    suspend fun capture(
        record: StudioChatRecord,
        request: RequestId? = record.lastRunRequest,
    ): StudioConversationSource? {
        if (!record.isCompactable || request == null || request != record.lastRunRequest) {
            return null
        }
        return attempts.conversationSource(record, request)
    }

    suspend fun isConversation(record: StudioChatRecord, request: RequestId): Boolean {
        if (!record.isCompactable || record.lastRunRequest != request) return false
        val receipt = attempts.continuation(HelperId(record.id), request)?.head ?: return false
        return receipt.request == request && receipt.policy == StudioAttemptPolicy.Conversation
    }

    suspend fun matches(record: StudioChatRecord?, source: StudioConversationSource): Boolean =
        record != null && attempts.conversationSource(record, source.request, source.actionFences) == source
}

internal val StudioHelperReceipt.isAutomaticConversationSource: Boolean
    get() = policy == StudioAttemptPolicy.Conversation && !isContinuationFenced && executionSnapshot != null &&
        (phase == StudioHelperPhase.Accepted || terminal?.outcome == StudioHelperTerminalOutcome.Completed)
