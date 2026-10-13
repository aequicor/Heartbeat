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
        if (selectedNative(chat) != PRIMARY_SUB_SESSION) return@withState
        val isOrganism = sessions.any { it.id == chat && it.isOrganism }
        if (isOrganism && (subSessions[chat] ?: PRIMARY_SUB_SESSION) != PRIMARY_SUB_SESSION) return@withState
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

/**
 * Handles a draft typed as `/compact` while compaction is offered: the chat's context is compacted instead of sending
 * a prompt and the draft is cleared once the request is queued; a request that cannot be queued now (no session yet,
 * a native sub-agent shown in the pane, a chat its owner compacts, a compaction already running) keeps the draft and
 * marks the pane failed. False when the draft is anything else.
 */
internal suspend fun compactStudioDraft(
    pipeline: PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>,
    compactions: StudioCompactionPort,
    paneId: Int,
): Boolean = with(pipeline) {
    var target: Pair<String?, CompactCommand>? = null
    withState {
        val command = compactCommand(draft(paneId)).takeIf { isCompactEnabled && attachments(paneId).isEmpty() }
        if (command != null) target = compactedChat(paneId) to command
    }
    val (chat, command) = target ?: return@with false
    val isQueued = chat != null && compactions.request(chat, command.instructions)
    log.i { "Compaction of the pane chat requested from the composer: queued=$isQueued" }
    updateState { if (isQueued) withDraft(paneId, "") else copy(failedPanes = (failedPanes + paneId).toImmutableSet()) }
    true
}

/** Correlates native acknowledgement with either a composer snapshot or a diagram-only message. */
internal suspend fun handleStudioSubmissionOutput(
    pipeline: PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>,
    machineState: AiStudioState,
    harnesses: StudioHarnesses,
    output: AiStudioOutput,
) = with(pipeline) {
    when (output) {
        // A new chat's harness claim travels with its submission; the turn uses and releases it. A chat
        // that failed to start, or whose session another request owns, runs no turn, so its claim is
        // released and the choice returns to the pane.
        is AiStudioOutput.SubmitFailed -> {
            if (output.submissionId.isNotEmpty()) harnesses.release(output.submissionId)
            updateState {
                if (output.submissionId.isEmpty()) {
                    restoreDraft(output, machineState)
                } else {
                    rejectSubmission(output.submissionId)
                        .finishDiagramSubmission(output.submissionId, DiagramDraftReasonUi.Rejected)
                }
            }
        }

        is AiStudioOutput.SubmitPrepared -> {
            updateState { prepareSubmission(output.submissionId, output.sessionId) }
        }

        is AiStudioOutput.SubmitAccepted -> {
            updateState {
                acceptSubmission(
                    output.submissionId,
                    output.sessionId,
                ).finishDiagramSubmission(output.submissionId, null)
            }
        }

        is AiStudioOutput.SubmitRejected -> {
            harnesses.release(output.submissionId)
            updateState {
                rejectSubmission(output.submissionId, output.sessionId)
                    .finishDiagramSubmission(output.submissionId, DiagramDraftReasonUi.Rejected)
            }
        }

        // Delivery results of engine questions are handled by the question bridge.
        is AiStudioOutput.PermissionAnswerFailed, is AiStudioOutput.RunEnded -> Unit
    }
}

private val log = Log.tag("StudioDraftSubmission")
