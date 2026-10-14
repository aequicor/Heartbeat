package io.aequicor.heartbeat.feature.aistudio.screenmodel.remote

import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioViewPredictionTest {
    @Test
    fun `the draft its caret and the sidebar are predicted like the app's store`() {
        val typed = AiStudioScreenState().predict(AiStudioScreenIntent.Suggestions.DraftChanged(paneId = 0, "hello"))
        assertEquals("hello", typed.draft(0))
        assertEquals(5, typed.draftCaret(0))

        assertEquals(2, typed.predict(AiStudioScreenIntent.Suggestions.CaretMoved(paneId = 0, caret = 2)).draftCaret(0))
        assertTrue(typed.predict(AiStudioScreenIntent.SetDrawerOpen(true)).sidebar.isDrawerOpen)
    }

    @Test
    fun `other intents wait for the app`() {
        val state = AiStudioScreenState()
        assertSame(state, state.predict(AiStudioScreenIntent.Retry))
        assertSame(state, state.predict(AiStudioScreenIntent.ToggleProjectSessions("project")))
        assertSame(state, state.predict(AiStudioScreenIntent.ToggleNoProjectSection))
    }
}
