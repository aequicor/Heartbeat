package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.CompactCommand
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioCompactionPort
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnesses
import io.aequicor.heartbeat.feature.aistudio.impl.domain.compactCommand
import kotlinx.collections.immutable.toImmutableSet
import pro.respawn.flowmvi.api.PipelineContext
import kotlin.uuid.Uuid

private val log = Log.tag("StudioDraftSubmission")

/**
 * Queues the exact draft snapshot before dispatch; only native acceptance may clear it. A new chat's harness choice
 * travels with the snapshot and leaves the pane; a refused dispatch returns both (chats keep their own choice).
 */
internal suspend fun submitStudioDraft(
    pipeline: PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>,
    machine: Machine<AiStudioState, AiStudioIntent, AiStudioOutput>,
    harnesses: StudioHarnesses,
    paneId: Int,
) = with(pipeline) {
    withState {
        val chat = panes.firstOrNull { it.id == paneId }?.sessionId
        if (selectedNative(chat) != PrimarySubSession) return@withState
        val isOrganism = sessions.any { it.id == chat && it.isOrganism }
        if (isOrganism && (subSessions[chat] ?: PrimarySubSession) != PrimarySubSession) return@withState
        val submissionId = Uuid.random().toString()
        val pending = pendingSubmission(paneId, submissionId)
        log.i { "Submit pane draft with attachments count=${pending.attachments.size}" }
        harnesses.claim(submissionId, pending.harnesses)
        updateState {
            queueSubmission(submissionId, pending).copy(harnessChoices = harnessChoices.withoutPane(paneId))
        }
        val result = sendTo(
            machine,
            AiStudioIntent.Public.Submit(
                paneId,
                pending.text,
                pending.attachments.map { ResourceRef("attachment:${it.id}", it.mediaType) },
                submissionId,
            ),
        )
        if (result != SendResult.Accepted) {
            harnesses.release(submissionId)
            updateState { rejectSubmission(submissionId) }
        }
    }
}

/** Effective native selection shared by transcript projection and submission; missing children fall back to root. */
internal fun AiStudioScreenState.selectedNative(sessionId: String?): String = subSessions[sessionId]?.takeIf { key ->
    nativeTrees[sessionId]?.sessions?.any { it.key == key && it.kind == SubSessionKindUi.Agent } == true
} ?: PrimarySubSession

/**
 * Handles a draft typed as `/compact` while compaction is offered: the chat's context is compacted instead of sending
 * a prompt and the draft is cleared once the request is queued; a request that cannot be queued now (no session yet,
 * a compaction already running) keeps the draft and marks the pane failed. False when the draft is anything else.
 */
internal suspend fun compactStudioDraft(
    pipeline: PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>,
    compactions: StudioCompactionPort,
    paneId: Int,
): Boolean = with(pipeline) {
    var target: Pair<String?, CompactCommand>? = null
    withState {
        val command = compactCommand(draft(paneId)).takeIf { isCompactEnabled && attachments(paneId).isEmpty() }
        if (command != null) target = panes.firstOrNull { it.id == paneId }?.sessionId to command
    }
    val (chat, command) = target ?: return@with false
    val isQueued = chat != null && compactions.request(chat, command.instructions)
    log.i { "Compaction of the pane chat requested from the composer: queued=$isQueued" }
    updateState { if (isQueued) withDraft(paneId, "") else copy(failedPanes = (failedPanes + paneId).toImmutableSet()) }
    true
}
