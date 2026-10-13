package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioDiagramActionsTest {
    private val pending = DiagramSubmissionUi("chat", "Button text")
    private val state = AiStudioScreenState(
        sessions = persistentListOf(SessionUi("chat", "Chat", null, Instant.DISTANT_PAST)),
        panes = persistentListOf(PaneUi(0, sessionId = "chat"), PaneUi(1, sessionId = "other")),
        drafts = persistentMapOf("chat" to "Typed", "other" to "Other draft"),
        draftAttachments = persistentMapOf("chat" to persistentListOf(AttachmentUi("f", "f.txt", "text/plain", 3))),
        diagramSubmissions = persistentMapOf("button" to pending),
        focusedPaneId = 1,
    )

    @Test
    fun `insertion appends to original chat and preserves attachments and another pane`() {
        val next = state.appendDiagramDraft(pending, null)
        assertEquals("Typed\nButton text", next.draft(0))
        assertEquals("Other draft", next.draft(1))
        assertEquals(state.draftAttachments, next.draftAttachments)
        assertEquals(next.draft(0).length, next.caretRequest(0)?.caret)
    }

    @Test
    fun `accepted send leaves typed draft and attachments intact`() {
        val next = state.finishDiagramSubmission("button", null)
        assertTrue(next.diagramSubmissions.isEmpty())
        assertEquals(state.drafts, next.drafts)
        assertEquals(state.draftAttachments, next.draftAttachments)
    }

    @Test
    fun `busy or rejected sends append once without scheduling a future send`() {
        DiagramDraftReasonUi.entries.forEach { reason ->
            val next = state.finishDiagramSubmission("button", reason)
            assertEquals("Typed\nButton text", next.draft(0))
            assertEquals(reason, next.diagramDraftReasons["chat"])
            assertTrue(next.diagramSubmissions.isEmpty())
            assertEquals(next, next.finishDiagramSubmission("button", reason))
        }
    }

    @Test
    fun `missing and read only chats cannot activate actions`() {
        assertTrue(state.canUseDiagramActions("chat"))
        assertFalse(state.canUseDiagramActions("other"))
        assertTrue(state.copy(subSessions = persistentMapOf("chat" to "child")).canUseDiagramActions("chat"))
        assertFalse(
            state.copy(sessions = persistentListOf(state.sessions.first().copy(isContinuable = false)))
                .canUseDiagramActions("chat"),
        )
        val deleted = state.copy(sessions = persistentListOf())
        assertEquals(deleted.drafts, deleted.appendDiagramDraft(pending, DiagramDraftReasonUi.Rejected).drafts)
    }
}
