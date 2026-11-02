package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatQueue
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedDelivery
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedOrigin
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator

/** Lost queue ACK is repaired only from the exact immutable envelope and its native admission receipt. */
internal suspend fun StudioChatQueue.reconcileCreatorReceipts(
    chat: String,
    attempts: StudioHelperAttempts,
    isRestoring: Boolean,
): StudioChatQueue {
    var active = activeRequest
    val retained = messages.mapNotNull { message ->
        val result = reconcileCreatorRow(chat, message, attempts, isRestoring)
        if (result.isSettled && active == message.id) active = null
        result.message
    }
    return copy(messages = retained, activeRequest = active)
}

private data class CreatorQueueResolution(val message: StudioQueuedMessage?, val isSettled: Boolean = false)

private suspend fun StudioChatQueue.reconcileCreatorRow(
    chat: String,
    message: StudioQueuedMessage,
    attempts: StudioHelperAttempts,
    isRestoring: Boolean,
): CreatorQueueResolution {
    val unchanged = CreatorQueueResolution(message)
    val origin = message.origin as? StudioQueuedOrigin.Creator ?: return unchanged
    check(creatorMessages[message.id] == message.copy(delivery = StudioQueuedDelivery.Pending)) {
        "Creator queue history contradicts its pending envelope"
    }
    val helper = HelperId(chat)
    val request = RequestId(message.id)
    val receipt = attempts.receipt(helper, request)
    return when {
        receipt == null -> unchanged

        receipt.phase == StudioHelperPhase.NotSubmitted && receipt.isFinal -> CreatorQueueResolution(null, true)

        !message.matchesCreatorReceipt(receipt, origin) -> unchanged

        receipt.phase in setOf(StudioHelperPhase.Accepted, StudioHelperPhase.Terminal) -> {
            if (message.matchesCreatorConfiguration(receipt)) {
                CreatorQueueResolution(null, receipt.phase == StudioHelperPhase.Terminal)
            } else {
                unchanged
            }
        }

        else -> {
            val reconciled = if (isRestoring && message.delivery == StudioQueuedDelivery.Uncertain) {
                attempts.cancelBeforeSubmission(helper, request, isFinal = false)
            } else {
                receipt
            }
            message.afterCreatorReconciliation(reconciled)
        }
    }
}

private fun StudioQueuedMessage.afterCreatorReconciliation(receipt: StudioHelperReceipt): CreatorQueueResolution =
    when {
        receipt.phase == StudioHelperPhase.NotSubmitted && receipt.isFinal -> CreatorQueueResolution(null, true)

        receipt.phase == StudioHelperPhase.NotSubmitted && receipt.isNativeEntryTracked ->
            CreatorQueueResolution(copy(delivery = StudioQueuedDelivery.Pending), true)

        else -> CreatorQueueResolution(this)
    }

private fun StudioQueuedMessage.matchesCreatorReceipt(
    receipt: StudioHelperReceipt,
    origin: StudioQueuedOrigin.Creator,
): Boolean = receipt.policy == StudioAttemptPolicy.Conversation && receipt.continuationOf == null &&
    receipt.fingerprint == HelperPrompt(RequestId(id), text).fingerprint() &&
    (receipt.causes == null || receipt.causes == setOf(RequestInitiator(origin.session, origin.request)))

private fun StudioQueuedMessage.matchesCreatorConfiguration(receipt: StudioHelperReceipt): Boolean {
    val captured = configuration ?: return false
    val actual = receipt.executionSnapshot ?: return false
    return actual.target == captured.target && actual.settings == settings &&
        actual.reasoningEffort == captured.reasoningEffort && actual.reviewTarget == captured.reviewTarget
}
