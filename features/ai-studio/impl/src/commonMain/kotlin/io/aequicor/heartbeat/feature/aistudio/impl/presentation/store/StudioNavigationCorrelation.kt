package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.MAX_STUDIO_PANES

/** Uses authoritative before/after panes so focus reflection order cannot detach another pane's pending input. */
internal fun navigationReplacedPanes(
    intent: AiStudioScreenIntent.Navigation,
    previous: AiStudioState,
    current: AiStudioState,
): Set<Int> {
    val before = previous as? AiStudioState.Ready ?: return emptySet()
    if (current !is AiStudioState.Ready) return emptySet()
    return when (intent) {
        is AiStudioScreenIntent.NewSession -> setOf(before.focusedPaneId)

        is AiStudioScreenIntent.OpenSession -> if (before.panes.any { it.sessionId == intent.sessionId }) {
            emptySet()
        } else {
            setOf(before.focusedPaneId)
        }

        is AiStudioScreenIntent.OpenBeside -> if (
            before.panes.any { it.sessionId == intent.sessionId && intent.sessionId != null } ||
            before.panes.size < MAX_STUDIO_PANES
        ) {
            emptySet()
        } else {
            before.panes.firstOrNull { it.id != before.focusedPaneId }?.let { setOf(it.id) }.orEmpty()
        }

        is AiStudioScreenIntent.ClosePane, is AiStudioScreenIntent.FocusPane,
        is AiStudioScreenIntent.SelectProject, is AiStudioScreenIntent.AddProject, AiStudioScreenIntent.Retry,
        -> emptySet()
    }
}
