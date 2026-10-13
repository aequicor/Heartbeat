package io.aequicor.heartbeat.feature.aistudio.screenmodel.remote

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.reduce
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.withDraft
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.withDraftCaret

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

    else -> this
}
