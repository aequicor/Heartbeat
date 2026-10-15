package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioAppUiTest {
    private val pane = PaneUi(0, projectId = "project")
    private val app = StudioAppUi("harness:gym:app", "Gym", "gym", null, "project", persistentSetOf("gym"))
    private val state = AiStudioScreenState(
        panes = persistentListOf(pane),
        apps = persistentMapOf(0 to app),
        harnessChoices = HarnessChoicesUi(panes = persistentMapOf(0 to persistentSetOf("gym"))),
    )

    @Test
    fun `a queued old app is hidden immediately after a pane changes its chat project or selected owners`() {
        assertEquals(app, state.appFor(pane))
        assertNull(state.appFor(pane.copy(sessionId = "new-chat")))
        assertNull(state.appFor(pane.copy(projectId = "other")))
        assertNull(state.copy(harnessChoices = HarnessChoicesUi()).appFor(pane))
        assertNull(state.appFor(pane.copy(id = 1)))
    }

    @Test
    fun `displayed first submission retains app selection while accepted and hidden submissions revoke it`() {
        val claim = SubmissionUi(0, "submission:request", "OK", persistentListOf(), harnesses = persistentSetOf("gym"))
        val queued = state.copy(harnessChoices = HarnessChoicesUi(), submissions = persistentMapOf("request" to claim))
        assertEquals(app, queued.appFor(pane))
        assertEquals(setOf("gym"), queued.surfaceHarnesses(pane))
        val prepared = queued.prepareSubmission("request", "chat")
        assertEquals(app, prepared.appFor(pane), "Prepared may arrive before pane reflection")
        assertEquals(setOf("gym"), prepared.surfaceHarnesses(pane.copy(sessionId = "chat")))
        assertEquals(emptySet(), prepared.surfaceHarnesses(pane.copy(sessionId = "other")))
        val hidden = queued.afterNavigation(AiStudioScreenIntent.NewSession(null))
        assertNull(hidden.appFor(pane))
        assertEquals(emptySet(), hidden.surfaceHarnesses(pane))
        assertNull(queued.acceptSubmission("request", "chat").appFor(pane))
        assertEquals(app, queued.rejectSubmission("request").appFor(pane))
    }

    @Test
    fun `an app selection and open gesture survive the screen wire format`() {
        val encoded = StudioViewJson.encodeToString(AiStudioScreenState.serializer(), state)
        assertEquals(state, StudioViewJson.decodeFromString(AiStudioScreenState.serializer(), encoded))
        val intent = AiStudioScreenIntent.OpenApp(0, app.surfaceId)
        val wire = StudioViewJson.encodeToString(AiStudioScreenIntent.serializer(), intent)
        assertEquals(intent, StudioViewJson.decodeFromString(AiStudioScreenIntent.serializer(), wire))
    }
}
