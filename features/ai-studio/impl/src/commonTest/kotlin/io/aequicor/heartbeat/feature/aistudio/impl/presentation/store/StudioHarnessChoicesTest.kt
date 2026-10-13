package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.screenui.harnessChoices
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
                HarnessOptionUi("compose", "Compose"),
                HarnessOptionUi("verify", "Verify"),
                HarnessOptionUi("chosen", "Chosen"),
            ),
            chats = persistentMapOf("chat" to persistentSetOf("chosen")),
            panes = persistentMapOf(1 to persistentSetOf("chosen")),
        ),
    )

    @Test
    fun `a chat selects only connected harnesses`() {
        val choices = state.harnessChoices(PaneUi(0, sessionId = "chat"))
        assertEquals(
            listOf(
                HarnessChoiceUi("compose", "Compose", isSelected = false),
                HarnessChoiceUi("verify", "Verify", isSelected = false),
                HarnessChoiceUi("chosen", "Chosen", isSelected = true),
            ),
            choices.toList(),
        )
    }

    @Test
    fun `a new chat selects only the choice kept on its pane`() {
        val choices = state.harnessChoices(PaneUi(1))
        assertEquals(listOf(false, false, true), choices.map { it.isSelected })

        val otherPane = state.harnessChoices(PaneUi(2))
        assertEquals(emptyList(), otherPane.filter { it.isSelected }.map { it.id })
    }

    @Test
    fun `a submitted pane choice leaves the pane`() {
        val chosen = HarnessChoicesUi().choosePane(1, "chosen", isSelected = true).choosePane(1, "other", true)
        assertEquals<Set<String>?>(setOf("chosen", "other"), chosen.panes[1])
        assertEquals<Set<String>?>(setOf("other"), chosen.choosePane(1, "chosen", isSelected = false).panes[1])
        assertTrue(chosen.withoutPane(1).panes.isEmpty())
        assertEquals(chosen, chosen.withoutPane(5))
    }

    @Test
    fun `a rejected new chat submission returns its choice to the pane without dropping a newer one`() {
        val initial = AiStudioScreenState(
            panes = persistentListOf(PaneUi(0)),
            drafts = persistentMapOf("pane:0" to "Question"),
            harnessChoices = HarnessChoicesUi(
                options = persistentListOf(HarnessOptionUi("chosen", "Chosen")),
                panes = persistentMapOf(0 to persistentSetOf("chosen")),
            ),
        )
        val pending = initial.pendingSubmission(0, "send")
        assertEquals<Set<String>>(setOf("chosen"), pending.harnesses)
        val queued = initial.queueSubmission("send", pending)
            .copy(harnessChoices = initial.harnessChoices.withoutPane(0).choosePane(0, "other", isSelected = true))

        val rejected = queued.rejectSubmission("send")
        assertEquals<Set<String>?>(setOf("chosen", "other"), rejected.harnessChoices.panes[0])
        assertEquals("Question", rejected.draft(0))

        val created = queued.prepareSubmission("send", "chat").rejectSubmission("send", "chat")
        assertEquals<Set<String>?>(setOf("other"), created.harnessChoices.panes[0], "a created chat keeps its own")
    }
}
