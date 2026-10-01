package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioSubmissionTest {
    private val file = AttachmentUi("file", "report.pdf", "application/pdf", 512)
    private val next = AttachmentUi("next", "photo.png", "image/png", 128)
    private val state = AiStudioScreenState(
        panes = persistentListOf(PaneUi(0, sessionId = "chat")),
        drafts = persistentMapOf("chat" to "Question"),
        draftAttachments = persistentMapOf("chat" to persistentListOf(file)),
        submissions = persistentMapOf("send" to SubmissionUi(0, "chat", "Question", persistentListOf(file))),
    )

    @Test
    fun `acceptance clears submitted files and text only once`() {
        val accepted = state.acceptSubmission("send")
        assertEquals("", accepted.draft(0))
        assertTrue(accepted.attachments(0).isEmpty())
        assertTrue(accepted.submissions.isEmpty())
        assertEquals(accepted, accepted.acceptSubmission("send"))
    }

    @Test
    fun `acceptance preserves text and files added while native validation was pending`() {
        val newer = state.copy(
            drafts = persistentMapOf("chat" to "New question"),
            draftAttachments = persistentMapOf("chat" to persistentListOf(file, next)),
        )
        val accepted = newer.acceptSubmission("send")
        assertEquals("New question", accepted.draft(0))
        assertEquals(listOf(next), accepted.attachments(0))
    }

    @Test
    fun `rejection preserves original draft and files`() {
        val rejected = state.rejectSubmission("send")
        assertEquals(state.drafts, rejected.drafts)
        assertEquals(state.draftAttachments, rejected.draftAttachments)
        assertTrue(0 in rejected.failedPanes)
        assertTrue(rejected.submissions.isEmpty())
    }

    @Test
    fun `switching to an unsupported model keeps files and blocks sending`() {
        val supported = InputSupportUi(mediaTypes = persistentListOf("application/pdf"))
        assertTrue(supported.accepts(state.attachments(0)))
        assertFalse(InputSupportUi().accepts(state.attachments(0)))
        assertEquals(listOf(file), state.attachments(0))
    }

    @Test
    fun `first acceptance moves the edited draft and added file into its created session`() {
        val newer = state.copy(
            drafts = persistentMapOf("submission:send" to "New question"),
            draftAttachments = persistentMapOf("submission:send" to persistentListOf(file, next)),
            submissions = persistentMapOf(
                "send" to SubmissionUi(0, "submission:send", "Question", persistentListOf(file), sessionId = "chat"),
            ),
        )
        val accepted = newer.acceptSubmission("send", "chat")
        assertEquals("New question", accepted.draft(0))
        assertEquals(listOf(next), accepted.attachments(0))
        assertFalse("submission:send" in accepted.drafts)
    }

    @Test
    fun `late acceptance after navigation updates its original session and preserves the visible foreign draft`() {
        val pending = state.copy(
            drafts = persistentMapOf("submission:send" to "New question", "other" to "Other draft"),
            draftAttachments = persistentMapOf("submission:send" to persistentListOf(file, next)),
            submissions = persistentMapOf(
                "send" to SubmissionUi(0, "submission:send", "Question", persistentListOf(file), sessionId = "chat"),
            ),
        ).afterNavigation(AiStudioScreenIntent.OpenSession("other"))
            .copy(panes = persistentListOf(PaneUi(0, sessionId = "other")))
        assertEquals("Other draft", pending.draft(0))
        val accepted = pending.acceptSubmission("send", "chat")
        assertEquals("Other draft", accepted.draft(0))
        assertTrue(accepted.attachments(0).isEmpty())
        assertEquals("New question", accepted.drafts["chat"])
        assertEquals(listOf(next), accepted.draftAttachments["chat"]?.toList())
    }

    @Test
    fun `new page during pending send never inherits the old files and rejection restores only the owning chat`() {
        val pending = state.copy(
            drafts = persistentMapOf("submission:send" to "Question"),
            draftAttachments = persistentMapOf("submission:send" to persistentListOf(file)),
            submissions = persistentMapOf(
                "send" to SubmissionUi(0, "submission:send", "Question", persistentListOf(file), sessionId = "chat"),
            ),
        ).afterNavigation(AiStudioScreenIntent.NewSession(null))
            .copy(panes = persistentListOf(PaneUi(0)))
            .withDraft(0, "A separate question")
        assertTrue(pending.attachments(0).isEmpty())
        val rejected = pending.rejectSubmission("send", "chat")
        assertEquals("A separate question", rejected.draft(0))
        assertTrue(rejected.attachments(0).isEmpty())
        assertEquals("Question", rejected.drafts["chat"])
        assertEquals(listOf(file), rejected.draftAttachments["chat"]?.toList())
        assertTrue(rejected.failedPanes.isEmpty())
    }

    @Test
    fun `editing between creation output and pane reflection stays with the pending submission`() {
        val initial = AiStudioScreenState(
            panes = persistentListOf(PaneUi(0)),
            drafts = persistentMapOf("pane:0" to "Question"),
            draftAttachments = persistentMapOf("pane:0" to persistentListOf(file)),
        )
        val queued = initial.queueSubmission("send", initial.pendingSubmission(0, "send"))
        val beforeReflection = queued.prepareSubmission("send", "chat").withDraft(0, "New question")
        val reflected = beforeReflection.copy(panes = persistentListOf(PaneUi(0, sessionId = "chat")))
        val accepted = reflected.acceptSubmission("send", "chat")
        assertEquals("New question", accepted.draft(0))
        assertTrue(accepted.attachments(0).isEmpty())
        val reverseOrder = queued.copy(panes = persistentListOf(PaneUi(0, sessionId = "chat")))
            .withDraft(0, "Another question").prepareSubmission("send", "chat").acceptSubmission("send", "chat")
        assertEquals("Another question", reverseOrder.draft(0))
    }

    @Test
    fun `opening beside retains the original pending composer despite focus moving to the new pane`() {
        val queued = state.copy(
            drafts = persistentMapOf("submission:send" to "Question"),
            draftAttachments = persistentMapOf("submission:send" to persistentListOf(file)),
            submissions = persistentMapOf(
                "send" to SubmissionUi(0, "submission:send", "Question", persistentListOf(file), sessionId = "chat"),
            ),
            panes = persistentListOf(PaneUi(0, sessionId = "chat"), PaneUi(1)),
            focusedPaneId = 1,
        ).afterNavigation(AiStudioScreenIntent.OpenBeside(null))
        assertEquals("Question", queued.draft(0))
        assertEquals(listOf(file), queued.attachments(0))
        assertEquals("", queued.draft(1))
    }

    @Test
    fun `session creation interleaved with opening beside does not replace the originating pane`() {
        val before = AiStudioState.Ready(
            listOf(StudioPane(0, isCreating = true)),
            0,
            RunSettings("model", ReasoningEffort.High, ApprovalMode.Ask),
        )
        val after = before.copy(panes = listOf(StudioPane(0, sessionId = "chat"), StudioPane(1)), focusedPaneId = 1)
        assertTrue(navigationReplacedPanes(AiStudioScreenIntent.OpenBeside(null), before, after).isEmpty())
    }

    @Test
    fun `rejection merges remaining files with new files in the created chat and drops transient file key`() {
        val pending = state.copy(
            drafts = persistentMapOf("submission:send" to "Question", "chat" to "Next question"),
            draftAttachments = persistentMapOf(
                "submission:send" to persistentListOf(file),
                "chat" to persistentListOf(next),
            ),
            submissions = persistentMapOf(
                "send" to SubmissionUi(0, "submission:send", "Question", persistentListOf(file), sessionId = "chat"),
            ),
        )
        val rejected = pending.rejectSubmission("send", "chat")
        assertEquals("Next question", rejected.draft(0))
        assertEquals(listOf(next, file), rejected.attachments(0))
        assertFalse("submission:send" in rejected.draftAttachments)
    }
}
