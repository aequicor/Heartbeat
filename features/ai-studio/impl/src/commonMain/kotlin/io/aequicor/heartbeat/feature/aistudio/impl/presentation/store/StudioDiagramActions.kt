package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.toImmutableMap

/** Text belongs to the original session and never consumes a composer draft or its attachments. */
@Immutable
data class DiagramSubmissionUi(val sessionId: String, val text: String) {
    override fun toString(): String = "DiagramSubmissionUi(sessionId=$sessionId)"
}

/** Why an attempted diagram send was appended to the draft instead of being delivered. */
enum class DiagramDraftReasonUi { Busy, Rejected }
internal fun AiStudioScreenState.canUseDiagramActions(sessionId: String): Boolean =
    sessions.any { it.id == sessionId && it.isContinuable } &&
        panes.any { it.sessionId == sessionId } && selectedNative(sessionId) == PRIMARY_SUB_SESSION &&
        (
            !sessions.any { it.id == sessionId && it.isOrganism } ||
                (subSessions[sessionId] ?: PRIMARY_SUB_SESSION) == PRIMARY_SUB_SESSION
        )

/** Null reason means native acceptance; a rejection appends once without overwriting subsequently typed text. */
internal fun AiStudioScreenState.finishDiagramSubmission(
    id: String,
    reason: DiagramDraftReasonUi?,
): AiStudioScreenState {
    val pending = diagramSubmissions[id] ?: return this
    val next = copy(diagramSubmissions = (diagramSubmissions - id).toImmutableMap())
    return if (reason == null) next else next.appendDiagramDraft(pending, reason)
}
internal fun AiStudioScreenState.appendDiagramDraft(
    pending: DiagramSubmissionUi,
    reason: DiagramDraftReasonUi?,
): AiStudioScreenState {
    val key = pending.sessionId
    if (sessions.none { it.id == key }) return this
    val previous = drafts[key].orEmpty()
    val text = if (previous.isEmpty()) pending.text else "$previous\n${pending.text}"
    val request = ComposerCaretRequestUi((draftCaretRequests[key]?.id ?: 0L) + 1L, text, text.length)
    val pane = panes.firstOrNull { it.sessionId == key && it.id == focusedPaneId }
        ?: panes.firstOrNull { it.sessionId == key }
    return copy(
        diagramFocusRequests = if (pane == null) {
            diagramFocusRequests
        } else {
            (diagramFocusRequests + (pane.id to ((diagramFocusRequests[pane.id] ?: 0L) + 1L))).toImmutableMap()
        },
        drafts = (drafts + (key to text)).toImmutableMap(),
        draftCarets = (draftCarets + (key to text.length)).toImmutableMap(),
        draftCaretRequests = (draftCaretRequests + (key to request)).toImmutableMap(),
        diagramDraftReasons = if (reason == null) {
            (diagramDraftReasons - key).toImmutableMap()
        } else {
            (diagramDraftReasons + (key to reason)).toImmutableMap()
        },
    )
}
