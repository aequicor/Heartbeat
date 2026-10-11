package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarness
import io.aequicor.heartbeat.feature.aistudio.impl.ui.harnessChoices
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioHarnessChoicesTest {
    private val state = AiStudioScreenState(
        harnessChoices = HarnessChoicesUi(
            options = persistentListOf(
                StudioHarness("everywhere", "Everywhere", isProfile = true, projects = emptySet()),
                StudioHarness("project", "Project", isProfile = false, projects = setOf("heartbeat")),
                StudioHarness("chosen", "Chosen", isProfile = false, projects = emptySet()),
            ),
            chats = persistentMapOf("chat" to persistentSetOf("chosen")),
            panes = persistentMapOf(1 to persistentSetOf("chosen")),
        ),
    )

    @Test
    fun `a chat shows its connections and harnesses active by scope as permanent`() {
        val choices = state.harnessChoices(PaneUi(0, sessionId = "chat"), projectId = null)
        assertEquals(
            listOf(
                HarnessChoiceUi("everywhere", "Everywhere", isSelected = true, isPermanent = true),
                HarnessChoiceUi("project", "Project", isSelected = false, isPermanent = false),
                HarnessChoiceUi("chosen", "Chosen", isSelected = true, isPermanent = false),
            ),
            choices.toList(),
        )
    }

    @Test
    fun `a new chat shows the choice kept on its pane and its project's harnesses`() {
        val choices = state.harnessChoices(PaneUi(1), projectId = "heartbeat")
        assertEquals(listOf(true, true, true), choices.map { it.isSelected })
        assertEquals(listOf(true, true, false), choices.map { it.isPermanent })

        val otherPane = state.harnessChoices(PaneUi(2), projectId = null)
        assertEquals(listOf("everywhere"), otherPane.filter { it.isSelected }.map { it.id })
    }

    @Test
    fun `a submitted pane choice leaves the pane`() {
        val chosen = HarnessChoicesUi().choosePane(1, "chosen", isSelected = true).choosePane(1, "other", true)
        assertEquals<Set<String>?>(setOf("chosen", "other"), chosen.panes[1])
        assertEquals<Set<String>?>(setOf("other"), chosen.choosePane(1, "chosen", isSelected = false).panes[1])
        assertTrue(chosen.withoutPane(1).panes.isEmpty())
        assertEquals(chosen, chosen.withoutPane(5))
    }
}
