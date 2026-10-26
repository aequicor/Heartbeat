package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.impl.domain.CompactCommand
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioCompactionPort
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnesses
import io.aequicor.heartbeat.feature.aistudio.impl.domain.compactCommand
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenAction
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.DiagramDraftReasonUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PRIMARY_SUB_SESSION
import io.aequicor.heartbeat.feature.aistudio.screenmodel.acceptSubmission
import io.aequicor.heartbeat.feature.aistudio.screenmodel.compactedChat
import io.aequicor.heartbeat.feature.aistudio.screenmodel.endSentPrompts
import io.aequicor.heartbeat.feature.aistudio.screenmodel.finishDiagramSubmission
import io.aequicor.heartbeat.feature.aistudio.screenmodel.isHarnessSelectionReady
import io.aequicor.heartbeat.feature.aistudio.screenmodel.pendingSubmission
import io.aequicor.heartbeat.feature.aistudio.screenmodel.prepareSubmission
import io.aequicor.heartbeat.feature.aistudio.screenmodel.queueSubmission
import io.aequicor.heartbeat.feature.aistudio.screenmodel.rejectSubmission
import io.aequicor.heartbeat.feature.aistudio.screenmodel.selectedNative
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withoutPane
import kotlinx.collections.immutable.toImmutableSet
import pro.respawn.flowmvi.api.PipelineContext
import kotlin.uuid.Uuid

/**
 * Queues the exact draft snapshot before dispatch; only native acceptance may clear it. A new chat's harness choice
 * travels with the snapshot and leaves the pane; a refused dispatch returns both (chats keep their own choice).
 * Only the explicit composer send sets [isDirectHumanSubmission]; automatic surface submissions keep it false.
 */
internal suspend fun submitStudioDraft(
    pipeline: PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>,
    machine: Machine<AiStudioState, AiStudioIntent, AiStudioOutput>,
    harnesses: StudioHarnesses,
    paneId: Int,
    environment: StudioChatEnvironment? = null,
    isDirectHumanSubmission: Boolean = false,
    isCurrent: (AiStudioScreenState) -> Boolean = { true },
) = with(pipeline) {
    withState {
        if (!isCurrent(this)) return@withState
        if (!canSubmitDraft(paneId)) return@withState
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
                environment,
                userInstruction = pending.text.takeIf { isDirectHumanSubmission },
            ),
        )
        if (result != SendResult.Accepted) {
            harnesses.release(submissionId)
            updateState { rejectSubmission(submissionId) }
        }
    }
}

/** Only the selected primary chat accepts composer submissions. */
private fun AiStudioScreenState.canSubmitDraft(paneId: Int): Boolean {
    val pane = panes.firstOrNull { it.id == paneId } ?: return false
    if (!isHarnessSelectionReady(pane)) return false
    val chat = pane.sessionId
    if (selectedNative(chat) != PRIMARY_SUB_SESSION) return false
    val isOrganism = sessions.any { it.id == chat && it.isOrganism }
    return !isOrganism || (subSessions[chat] ?: PRIMARY_SUB_SESSION) == PRIMARY_SUB_SESSION
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

        // The engine may never echo the prompt of a failed turn; its sent message leaves with the next transcript.
        is AiStudioOutput.RunEnded -> updateState { endSentPrompts(output.sessionId) }

        // Delivery results of engine questions are handled by the question bridge.
        is AiStudioOutput.PermissionAnswerFailed -> Unit
    }
}

private val log = Log.tag("StudioDraftSubmission")
