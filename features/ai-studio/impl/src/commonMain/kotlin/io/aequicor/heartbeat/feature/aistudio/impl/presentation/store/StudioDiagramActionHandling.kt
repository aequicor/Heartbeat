package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.DiagramDraftReasonUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.DiagramSubmissionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.appendDiagramDraft
import io.aequicor.heartbeat.feature.aistudio.screenmodel.canUseDiagramActions
import io.aequicor.heartbeat.feature.aistudio.screenmodel.finishDiagramSubmission
import kotlinx.collections.immutable.toImmutableMap
import pro.respawn.flowmvi.api.PipelineContext
import kotlin.uuid.Uuid

internal suspend fun handleStudioDiagramAction(
    pipeline: PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>,
    machine: Machine<AiStudioState, AiStudioIntent, AiStudioOutput>,
    intent: AiStudioScreenIntent.DiagramAction,
) = with(pipeline) {
    withState {
        if (!canUseDiagramActions(intent.sessionId) || intent.text.isBlank() || intent.text.length > MAX_DIAGRAM_TEXT) {
            return@withState
        }
        if (diagramSubmissions.values.any { it.sessionId == intent.sessionId }) return@withState
        val pending = DiagramSubmissionUi(intent.sessionId, intent.text)
        if (!intent.isSend) {
            updateState { appendDiagramDraft(pending, null) }
            return@withState
        }
        sendDiagramMessage(pipeline, machine, pending)
    }
}

private suspend fun sendDiagramMessage(
    pipeline: PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>,
    machine: Machine<AiStudioState, AiStudioIntent, AiStudioOutput>,
    pending: DiagramSubmissionUi,
) = with(pipeline) {
    val id = Uuid.random().toString()
    updateState { copy(diagramSubmissions = (diagramSubmissions + (id to pending)).toImmutableMap()) }
    val result = sendTo(machine, AiStudioIntent.Public.FollowUp(pending.sessionId, pending.text, id))
    if (result != SendResult.Accepted) {
        updateState {
            val reason = if (pending.sessionId in
                running
            ) {
                DiagramDraftReasonUi.Busy
            } else {
                DiagramDraftReasonUi.Rejected
            }
            finishDiagramSubmission(id, reason)
        }
    }
}

private const val MAX_DIAGRAM_TEXT = 16 * 1024
