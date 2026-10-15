package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioContextPanelUiTest {
    @Test
    fun `changing a chat project or selected owner hides the old context panel immediately`() {
        val panel = StudioContextPanelUi(null, "project", persistentSetOf("gym"))
        val state = AiStudioScreenState(
            contextPanels = persistentMapOf(0 to panel),
            harnessChoices = HarnessChoicesUi(panes = persistentMapOf(0 to persistentSetOf("gym"))),
        )
        assertEquals(panel, state.contextPanelFor(PaneUi(0, projectId = "project")))
        assertNull(state.contextPanelFor(PaneUi(0, sessionId = "chat", projectId = "project")))
        assertNull(state.contextPanelFor(PaneUi(0, projectId = "other")))
        assertNull(state.copy(harnessChoices = HarnessChoicesUi()).contextPanelFor(PaneUi(0, projectId = "project")))
    }
}
