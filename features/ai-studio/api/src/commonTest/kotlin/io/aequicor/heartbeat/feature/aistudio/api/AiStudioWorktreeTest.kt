package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test

class AiStudioWorktreeTest {
    private val settings = RunSettings("route", ReasoningEffort.High, ApprovalMode.Ask)
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0, projectId = "project"), StudioPane(1, projectId = "project")),
        focusedPaneId = 0,
        settings = settings,
        isWorktreeAvailable = true,
    )

    @Test
    fun `mode is independently chosen per new pane and passed to creation`() {
        val selected = ready.copy(panes = listOf(ready.panes[0].copy(isWorktree = true), ready.panes[1]))
        AiStudioMachineSpec.assertTransition(ready, AiStudioIntent.Public.SelectWorktree(0, true), selected)
        AiStudioMachineSpec.assertTransition(
            selected,
            AiStudioIntent.Public.Submit(0, "Implement"),
            selected.copy(panes = listOf(selected.panes[0].copy(isCreating = true), selected.panes[1])),
            effects = listOf(AiStudioEffect.CreateSession(0, "project", "Implement", settings, isWorktree = true)),
        )
    }

    @Test
    fun `checkout changes require an enabled mode and idle new pane`() {
        AiStudioMachineSpec.assertIgnored(
            ready.copy(isWorktreeAvailable = false),
            AiStudioIntent.Public.SelectWorktree(0, true),
        )
        AiStudioMachineSpec.assertIgnored(
            ready.copy(panes = listOf(StudioPane(0, sessionId = "chat"))),
            AiStudioIntent.Public.SelectWorktree(0, true),
        )
        AiStudioMachineSpec.assertIgnored(
            ready.copy(panes = listOf(StudioPane(0, projectId = "project", isCreating = true))),
            AiStudioIntent.Public.SelectWorktree(0, true),
        )
        AiStudioMachineSpec.assertIgnored(
            ready.copy(panes = listOf(StudioPane(0))),
            AiStudioIntent.Public.SelectWorktree(0, true),
        )
    }

    @Test
    fun `failed preparation releases the pane and returns its prompt`() {
        val selected = ready.copy(panes = listOf(StudioPane(0, projectId = "project", isWorktree = true)))
        AiStudioMachineSpec.assertTransition(
            selected.copy(panes = listOf(selected.panes.single().copy(isCreating = true))),
            AiStudioIntent.Internal.CreateFailed(0, "Draft"),
            selected,
            outputs = listOf(AiStudioOutput.SubmitFailed(0, "Draft")),
        )
    }
}
