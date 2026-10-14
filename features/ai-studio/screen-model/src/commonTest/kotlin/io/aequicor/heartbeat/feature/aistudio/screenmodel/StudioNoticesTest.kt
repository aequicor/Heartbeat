package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioNoticesTest {
    private val at = Instant.parse("2026-10-14T08:00:00Z")
    private val state = AiStudioScreenState(
        panes = persistentListOf(PaneUi(0, sessionId = "chat"), PaneUi(1, sessionId = "other")),
        sessions = persistentListOf(SessionUi("chat", "Chat", null, at), SessionUi("other", "Other", null, at)),
        drafts = persistentMapOf("chat" to "Keep this prompt"),
        draftAttachments = persistentMapOf("chat" to persistentListOf(AttachmentUi("a", "a.txt", "text/plain", 4))),
        failedPanes = persistentSetOf(0, 1),
        attachmentErrorPanes = persistentSetOf(0, 1),
        harnessChoices = HarnessChoicesUi(failedChats = persistentSetOf("chat", "other")),
        diagramDraftReasons = persistentMapOf("chat" to DiagramDraftReasonUi.Busy),
    )

    @Test
    fun `dismissing local notices preserves the draft attachments and unrelated errors`() {
        val submit = state.dismiss(StudioNoticeUi.SubmitFailed)
        assertEquals(setOf(1), submit.failedPanes)
        assertEquals(state.attachmentErrorPanes, submit.attachmentErrorPanes)
        assertEquals(setOf(1), state.dismiss(StudioNoticeUi.AttachmentFailed).attachmentErrorPanes)
        assertEquals(setOf("other"), state.dismiss(StudioNoticeUi.HarnessFailed).harnessChoices.failedChats)
        assertTrue(state.dismiss(StudioNoticeUi.DiagramDraft).diagramDraftReasons.isEmpty())
        for (notice in listOf(
            StudioNoticeUi.SubmitFailed,
            StudioNoticeUi.AttachmentFailed,
            StudioNoticeUi.HarnessFailed,
            StudioNoticeUi.DiagramDraft,
        )) {
            val dismissed = state.dismiss(notice)
            assertEquals(state.drafts, dismissed.drafts)
            assertEquals(state.draftAttachments, dismissed.draftAttachments)
            assertTrue(dismissed.noticeDismissals.isEmpty())
        }
    }

    @Test
    fun `a later rejected submission and repeated diagram notice appear again`() {
        val cleared = state.dismiss(StudioNoticeUi.SubmitFailed).dismiss(StudioNoticeUi.DiagramDraft)
        val pending = cleared.pendingSubmission(0, "new")
        val failedAgain = cleared.queueSubmission("new", pending).rejectSubmission("new", "chat")
        assertTrue(0 in failedAgain.failedPanes)
        val diagramAgain = cleared.appendDiagramDraft(
            DiagramSubmissionUi("chat", "diagram"),
            DiagramDraftReasonUi.Busy,
        )
        assertEquals(DiagramDraftReasonUi.Busy, diagramAgain.diagramDraftReasons["chat"])
    }

    @Test
    fun `mirrored failures stay owned by the machine and are dismissed only in their pane`() {
        val failed = state.copy(projectErrorPane = 0, stopFailures = persistentSetOf("chat"))
        val dismissed = failed.dismiss(StudioNoticeUi.ProjectFailed).dismiss(StudioNoticeUi.StopFailed)
        assertEquals(0, dismissed.projectErrorPane)
        assertEquals(setOf("chat"), dismissed.stopFailures)
        assertEquals(setOf(StudioNoticeUi.ProjectFailed, StudioNoticeUi.StopFailed), dismissed.dismissedNotices(0))
        assertTrue(dismissed.dismissedNotices(1).isEmpty())
        assertEquals(dismissed, dismissed.copy(now = at).pruneNoticeDismissals().copy(now = dismissed.now))
    }

    @Test
    fun `resolved and explicitly retried failures forget their dismissal`() {
        val dismissed = state.copy(projectErrorPane = 0, stopFailures = persistentSetOf("chat"))
            .dismiss(StudioNoticeUi.ProjectFailed).dismiss(StudioNoticeUi.StopFailed)
        val resolved = dismissed.copy(projectErrorPane = null, stopFailures = persistentSetOf()).pruneNoticeDismissals()
        val failedAgain = resolved.copy(projectErrorPane = 0, stopFailures = persistentSetOf("chat"))
        assertTrue(failedAgain.dismissedNotices(0).isEmpty())
        val retry = dismissed.afterNavigation(AiStudioScreenIntent.AddProject(0))
        assertFalse(StudioNoticeUi.ProjectFailed in retry.dismissedNotices(0))
        assertFalse(
            StudioNoticeUi.StopFailed in dismissed.revealNotice(0, StudioNoticeUi.StopFailed).dismissedNotices(0),
        )
    }

    @Test
    fun `navigation and changed model choices do not inherit an old dismissal`() {
        val dismissed = state.dismiss(StudioNoticeUi.ConnectModel)
        assertTrue(StudioNoticeUi.ConnectModel in dismissed.dismissedNotices(0))
        val switched = dismissed.copy(panes = persistentListOf(PaneUi(0, sessionId = "other")))
        assertTrue(switched.dismissedNotices(0).isEmpty())
        assertTrue(switched.pruneNoticeDismissals().copy(panes = state.panes).dismissedNotices(0).isEmpty())
        val changedModels = dismissed.copy(models = persistentListOf(ModelUi("model", "Model")))
        assertTrue(changedModels.dismissedNotices(0).isEmpty())
        val fresh = AiStudioScreenState(panes = persistentListOf(PaneUi(0)))
            .dismiss(StudioNoticeUi.ConnectModel).afterNavigation(AiStudioScreenIntent.NewSession(null))
        assertTrue(fresh.dismissedNotices(0).isEmpty())
    }

    @Test
    fun `read only and unsupported stop hints reappear when their source changes`() {
        val restricted = state.copy(
            sessions = persistentListOf(state.sessions[0].copy(isContinuable = false)),
            running = persistentSetOf("chat"),
            uncancellable = persistentSetOf("chat"),
            runStartedAt = persistentMapOf("chat" to at),
        ).dismiss(StudioNoticeUi.ReadOnly).dismiss(StudioNoticeUi.StopUnsupported)
        assertEquals(setOf(StudioNoticeUi.ReadOnly, StudioNoticeUi.StopUnsupported), restricted.dismissedNotices(0))
        val changed = restricted.copy(sessions = state.sessions, running = persistentSetOf()).pruneNoticeDismissals()
        assertTrue(changed.noticeDismissals.isEmpty())
        assertEquals(restricted.running, restricted.dismiss(StudioNoticeUi.StopUnsupported).running)
    }

    @Test
    fun `stale notice clicks cannot clear failures or hide notices of a replacement chat`() {
        val shown = state.copy(
            projectErrorPane = 0,
            stopFailures = persistentSetOf("chat", "other"),
            diagramDraftReasons = persistentMapOf(
                "chat" to DiagramDraftReasonUi.Busy,
                "other" to DiagramDraftReasonUi.Rejected,
            ),
        )
        val switched = shown.afterNavigation(AiStudioScreenIntent.OpenSession("other"))
            .copy(panes = persistentListOf(PaneUi(0, sessionId = "other")))
        for (notice in StudioNoticeUi.entries) {
            val stale = AiStudioScreenIntent.DismissNotice(shown.noticeDismissal(shown.panes.first(), notice))
            assertEquals(
                switched,
                switched.dismissNotice(stale),
                "A stale $notice click must leave the new chat intact",
            )
        }

        assertEquals(setOf(1), switched.dismiss(StudioNoticeUi.SubmitFailed).failedPanes)
        assertEquals(setOf(1), switched.dismiss(StudioNoticeUi.AttachmentFailed).attachmentErrorPanes)
        assertEquals(setOf("chat"), switched.dismiss(StudioNoticeUi.HarnessFailed).harnessChoices.failedChats)
        assertEquals(
            mapOf("chat" to DiagramDraftReasonUi.Busy),
            switched.dismiss(StudioNoticeUi.DiagramDraft).diagramDraftReasons,
        )
        for (notice in listOf(StudioNoticeUi.ProjectFailed, StudioNoticeUi.StopFailed, StudioNoticeUi.ConnectModel)) {
            assertTrue(notice in switched.dismiss(notice).dismissedNotices(0))
        }
    }

    @Test
    fun `stale notice clicks cannot follow a project or sub-session switch`() {
        val draft = AiStudioScreenState(panes = persistentListOf(PaneUi(0, projectId = "first")))
        val projectNotice = AiStudioScreenIntent.DismissNotice(
            draft.noticeDismissal(draft.panes.single(), StudioNoticeUi.ConnectModel),
        )
        val newProject = draft.copy(panes = persistentListOf(PaneUi(0, projectId = "second")))
        assertEquals(newProject, newProject.dismissNotice(projectNotice))
        assertTrue(StudioNoticeUi.ConnectModel in newProject.dismiss(StudioNoticeUi.ConnectModel).dismissedNotices(0))

        val harnessNotice = AiStudioScreenIntent.DismissNotice(
            state.noticeDismissal(state.panes.first(), StudioNoticeUi.HarnessFailed),
        )
        val newChild = state.copy(subSessions = persistentMapOf("chat" to "child"))
        assertEquals(newChild, newChild.dismissNotice(harnessNotice))
        assertEquals(setOf("other"), newChild.dismiss(StudioNoticeUi.HarnessFailed).harnessChoices.failedChats)
    }

    @Test
    fun `a delayed close of a resolved source cannot hide its next notice`() {
        val shown = state.copy(
            projectErrorPane = 0,
            stopFailures = persistentSetOf("chat"),
            sessions = persistentListOf(state.sessions.first().copy(isContinuable = false)),
            running = persistentSetOf("chat"),
            uncancellable = persistentSetOf("chat"),
            runStartedAt = persistentMapOf("chat" to at),
        )
        val resolved = shown.copy(
            projectErrorPane = null,
            stopFailures = persistentSetOf(),
            sessions = state.sessions,
            running = persistentSetOf(),
        )
        for (notice in listOf(
            StudioNoticeUi.ProjectFailed,
            StudioNoticeUi.StopFailed,
            StudioNoticeUi.ReadOnly,
            StudioNoticeUi.StopUnsupported,
        )) {
            val stale = AiStudioScreenIntent.DismissNotice(shown.noticeDismissal(shown.panes.first(), notice))
            val afterClose = resolved.dismissNotice(stale)
            assertEquals(resolved, afterClose, "A resolved $notice source cannot acquire a dismissal")
            assertTrue(shown.copy(noticeDismissals = afterClose.noticeDismissals).dismissedNotices(0).isEmpty())
        }
    }

    private fun AiStudioScreenState.dismiss(notice: StudioNoticeUi) =
        dismissNotice(AiStudioScreenIntent.DismissNotice(noticeDismissal(panes.first { it.id == 0 }, notice)))
}
