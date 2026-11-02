package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioAutopilotDraftTest {
    private val choice = AutoContinueUi(mode = AutoContinueModeUi.Autopilot, modelId = "other-route")
    private val draft = AiStudioScreenState(
        panes = persistentListOf(PaneUi(0)),
        drafts = persistentMapOf("pane:0" to "first task"),
        newChatAutoContinue = persistentMapOf(0 to choice),
    )

    @Test
    fun `choice travels with its draft and a refused create restores it`() {
        val pending = draft.pendingSubmission(0, "request")
        val queued = draft.queueSubmission("request", pending)
        assertEquals(choice, pending.autoContinue)
        assertTrue(queued.newChatAutoContinue.isEmpty())
        assertEquals(choice, queued.rejectSubmission("request").newChatAutoContinue[0])
        val navigated = queued.afterNavigation(AiStudioScreenIntent.NewSession(null))
        assertTrue(navigated.rejectSubmission("request").newChatAutoContinue.isEmpty())
    }

    @Test
    fun `a reused pane never inherits the previous new chat choice`() {
        assertTrue(draft.afterNavigation(AiStudioScreenIntent.NewSession(null)).newChatAutoContinue.isEmpty())
    }
}
