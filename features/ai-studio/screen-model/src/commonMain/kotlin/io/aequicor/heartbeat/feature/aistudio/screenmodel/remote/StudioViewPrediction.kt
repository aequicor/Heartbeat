package io.aequicor.heartbeat.feature.aistudio.screenmodel.remote

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.reduce
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.withDraft
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.withDraftCaret
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenIntent
import io.aequicor.heartbeat.feature.checklist.screenmodel.predictDraft
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.predictDraft
import kotlinx.collections.immutable.toImmutableMap

/**
 * What a remote view shows for [intent] before the app has applied it: the draft, its caret and the sidebar
 * change at once, with the same reducers as the app's store, so typing does not wait for the round trip. Other
 * intents wait for the app.
 */
fun AiStudioScreenState.predict(intent: AiStudioScreenIntent): AiStudioScreenState = when {
    intent is AiStudioScreenIntent.Suggestions.DraftChanged ->
        withDraft(intent.paneId, intent.text).withDraftCaret(intent.paneId, intent.text.length)

    intent is AiStudioScreenIntent.Suggestions.CaretMoved -> withDraftCaret(intent.paneId, intent.caret)

    intent is AiStudioScreenIntent.Sidebar -> copy(sidebar = sidebar.reduce(intent))

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
