package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import pro.respawn.flowmvi.api.PipelineContext
import kotlin.uuid.Uuid

private val log = Log.tag("StudioDraftSubmission")

/** Queues the exact draft snapshot before dispatch; only native acceptance may clear it. */
internal suspend fun submitStudioDraft(
    pipeline: PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>,
    machine: Machine<AiStudioState, AiStudioIntent, AiStudioOutput>,
    paneId: Int,
) = with(pipeline) {
    withState {
        val chat = panes.firstOrNull { it.id == paneId }?.sessionId
        if (selectedNative(chat) != PrimarySubSession) return@withState
        val submissionId = Uuid.random().toString()
        val pending = pendingSubmission(paneId, submissionId)
        log.i { "Submit pane draft with attachments count=${pending.attachments.size}" }
        updateState { queueSubmission(submissionId, pending) }
        val result = sendTo(
            machine,
            AiStudioIntent.Public.Submit(
                paneId,
                pending.text,
                pending.attachments.map { ResourceRef("attachment:${it.id}", it.mediaType) },
                submissionId,
            ),
        )
        if (result != SendResult.Accepted) updateState { rejectSubmission(submissionId) }
    }
}

/** Effective native selection shared by transcript projection and submission; missing children fall back to root. */
internal fun AiStudioScreenState.selectedNative(sessionId: String?): String = subSessions[sessionId]?.takeIf { key ->
    nativeTrees[sessionId]?.sessions?.any { it.key == key && it.kind == SubSessionKindUi.Agent } == true
} ?: PrimarySubSession
