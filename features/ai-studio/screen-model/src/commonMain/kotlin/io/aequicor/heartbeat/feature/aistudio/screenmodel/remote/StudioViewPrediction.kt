package io.aequicor.heartbeat.feature.aistudio.screenmodel.remote

import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.isAttachmentPending
import io.aequicor.heartbeat.feature.aistudio.screenmodel.reduce
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraftCaret
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenIntent
import io.aequicor.heartbeat.feature.checklist.screenmodel.predictDraft
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.predictDraft
import kotlinx.collections.immutable.toImmutableMap

/**
 * What a remote view shows before the app acknowledges [intent]: idempotent draft, caret and sidebar assignments
 * use the same reducers as the app. Toggles wait for the app, since an intermediate frame can already include
 * their effect while still carrying the previous acknowledgement.
 */
fun AiStudioScreenState.predict(intent: AiStudioScreenIntent): AiStudioScreenState = when {
    intent is AiStudioScreenIntent.Suggestions.DraftChanged ->
        withDraft(intent.paneId, intent.text).withDraftCaret(intent.paneId, intent.text.length)

    intent is AiStudioScreenIntent.Suggestions.CaretMoved -> withDraftCaret(intent.paneId, intent.caret)

    intent is AiStudioScreenIntent.Sidebar -> when (intent) {
        is AiStudioScreenIntent.ShowSidebarMode, is AiStudioScreenIntent.SearchChanged,
        is AiStudioScreenIntent.SetDrawerOpen,
        -> copy(sidebar = sidebar.reduce(intent))

        AiStudioScreenIntent.ToggleSearch, is AiStudioScreenIntent.ToggleProject,
        is AiStudioScreenIntent.ToggleProjectSessions, AiStudioScreenIntent.ToggleNoProjectSection,
        AiStudioScreenIntent.ToggleProjectsSection, AiStudioScreenIntent.ToggleRecentSection,
        AiStudioScreenIntent.ToggleSidebar,
        -> this
    }

    intent is AiStudioScreenIntent.ImportAttachments && !isAttachmentPending(intent.paneId) -> copy(
        attachmentRequests = (
            attachmentRequests + (
                "remote-import:${intent.paneId}" to draftKey(
                    intent.paneId,
                )
            )
        ).toImmutableMap(),
    )

    intent is AiStudioScreenIntent.QuestionInput -> predictQuestion(intent)

    intent is AiStudioScreenIntent.ChecklistInput -> predictChecklist(intent)

    else -> this
}

private fun AiStudioScreenState.predictQuestion(intent: AiStudioScreenIntent.QuestionInput): AiStudioScreenState {
    // Toggles cannot be replayed over a committed child snapshot whose root ack has not arrived yet.
    if (intent.input !is QuestionnaireScreenIntent.TextChanged) return this
    val child = remoteEmbeds.questions[intent.source]?.takeIf { it.instance == intent.instance } ?: return this
    val changed = child.copy(screen = child.screen.predictDraft(intent.input))
    val questions = (remoteEmbeds.questions + (intent.source to changed)).toImmutableMap()
    return copy(remoteEmbeds = remoteEmbeds.copy(questions = questions))
}

private fun AiStudioScreenState.predictChecklist(intent: AiStudioScreenIntent.ChecklistInput): AiStudioScreenState {
    if (intent.input !is ChecklistScreenIntent.Text) return this
    val child = remoteEmbeds.checklists[intent.id]?.takeIf { it.instance == intent.instance } ?: return this
    val changed = child.copy(screen = child.screen.predictDraft(intent.input))
    val checklists = (remoteEmbeds.checklists + (intent.id to changed)).toImmutableMap()
    return copy(remoteEmbeds = remoteEmbeds.copy(checklists = checklists))
}
