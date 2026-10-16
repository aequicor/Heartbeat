package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioDefaultHarnessesTest {
    private val state = AiStudioScreenState(
        panes = persistentListOf(PaneUi(0)),
        harnessChoices = HarnessChoicesUi(
            options = persistentListOf(
                HarnessOptionUi("shared", "Shared", isDefaultForNewSessions = true),
                HarnessOptionUi(
                    "project",
                    "Project",
                    projectIds = persistentSetOf("project-a"),
                    isDefaultForNewSessions = true,
                ),
                HarnessOptionUi("manual", "Manual"),
            ),
            chats = persistentMapOf("existing" to persistentSetOf("manual")),
        ),
    )

    @Test
    fun `unknown defaults block only ordinary new chats`() {
        val loading = state.copy(harnessChoices = state.harnessChoices.copy(isReady = false))
        assertTrue(!loading.isHarnessSelectionReady(PaneUi(0)))
        assertTrue(!loading.isHarnessSelectionReady(PaneUi(0, isWorktree = true)))
        assertTrue(loading.isHarnessSelectionReady(PaneUi(0, sessionId = "existing")))
        assertTrue(loading.isHarnessSelectionReady(PaneUi(0, isOrganism = true)))
        assertTrue(AiStudioScreenState().isHarnessSelectionReady(PaneUi(0)))
    }

    @Test
    fun `defaults follow new chat availability and never change existing chat connections`() {
        assertEquals(setOf("shared"), state.selectedHarnesses(PaneUi(0)))
        assertEquals(setOf("shared"), state.selectedHarnesses(PaneUi(0, projectId = "other")))
        val project = PaneUi(0, projectId = "project-a")
        assertEquals(setOf("shared", "project"), state.selectedHarnesses(project))
        assertEquals(setOf("shared", "project"), state.selectedHarnesses(project.copy(isWorktree = true)))
        assertEquals(setOf("manual"), state.selectedHarnesses(PaneUi(0, sessionId = "existing")))
        assertTrue(state.selectedHarnesses(PaneUi(0, sessionId = "unconnected")).isEmpty())
    }

    @Test
    fun `clearing defaults survives refresh and a rejected first submission`() {
        val cleared = state.copy(
            harnessChoices = state.harnessChoices.choosePane(0, "shared", false, state.selectedHarnesses(PaneUi(0))),
        ).pruneHarnessChoices()
        assertEquals<Set<String>?>(emptySet(), cleared.harnessChoices.panes[0])
        assertTrue(cleared.selectedHarnesses(PaneUi(0)).isEmpty())
        val pending = cleared.pendingSubmission(0, "send")
        assertTrue(pending.harnesses.isEmpty())
        val queued = cleared.queueSubmission("send", pending)
            .copy(harnessChoices = cleared.harnessChoices.withoutPane(0))
        assertTrue(queued.surfaceHarnesses(PaneUi(0)).isEmpty())
        val rejected = queued.rejectSubmission("send")
        assertTrue(rejected.selectedHarnesses(PaneUi(0)).isEmpty())
        assertTrue(rejected.pruneHarnessChoices().pendingSubmission(0, "retry").harnesses.isEmpty())
    }

    @Test
    fun `manual choice freezes defaults until a new chat replaces the pane`() {
        val later = HarnessOptionUi("later", "Later", isDefaultForNewSessions = true)
        val chosen = state.copy(
            harnessChoices = state.harnessChoices.choosePane(0, "manual", true, state.selectedHarnesses(PaneUi(0))),
        )
        val refreshed = chosen.copy(
            harnessChoices = chosen.harnessChoices.copy(
                options = (state.harnessChoices.options + later).toImmutableList(),
            ),
        )
        assertEquals(setOf("shared", "manual"), refreshed.selectedHarnesses(PaneUi(0)))
        val next = refreshed.afterNavigation(AiStudioScreenIntent.NewSession(null))
        assertEquals(setOf("shared", "later"), next.selectedHarnesses(PaneUi(0)))
    }

    @Test
    fun `first submission keeps its default snapshot for surfaces while the library changes`() {
        val pending = state.pendingSubmission(0, "send")
        assertEquals(setOf("shared"), pending.harnesses)
        val queued = state.queueSubmission("send", pending)
            .copy(harnessChoices = HarnessChoicesUi())
        assertEquals(setOf("shared"), queued.surfaceHarnesses(PaneUi(0)))
        assertTrue(queued.afterNavigation(AiStudioScreenIntent.NewSession(null)).surfaceHarnesses(PaneUi(0)).isEmpty())
    }

    @Test
    fun `organisms do not receive defaults and preserve their manual selection`() {
        val pane = PaneUi(0, isOrganism = true)
        val organism = state.copy(panes = persistentListOf(pane))
        assertTrue(organism.selectedHarnesses(pane).isEmpty())
        assertTrue(organism.pendingSubmission(0, "send").harnesses.isEmpty())
        val selected = organism.copy(harnessChoices = organism.harnessChoices.choosePane(0, "manual", true))
        assertEquals(setOf("manual"), selected.selectedHarnesses(pane))
    }
}
