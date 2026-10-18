package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test

class AiStudioPlanningTest {
    private val settings = RunSettings("route", ReasoningEffort.High, ApprovalMode.Ask)
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0), StudioPane(1, projectId = "project")),
        focusedPaneId = 0,
        settings = settings,
    )

    @Test
    fun `planning mode is chosen per new pane and passed to creation`() {
        val selected = ready.copy(panes = listOf(ready.panes[0].copy(isPlanning = true), ready.panes[1]))
        AiStudioMachineSpec.assertTransition(ready, AiStudioIntent.Public.SelectPlanning(0, true), selected)
        AiStudioMachineSpec.assertTransition(selected, AiStudioIntent.Public.SelectPlanning(0, false), ready)
        AiStudioMachineSpec.assertTransition(
            selected,
            AiStudioIntent.Public.Submit(0, " Plan the feature "),
            selected.copy(
                panes = listOf(selected.panes[0].copy(isCreating = true, createRequestId = 0), selected.panes[1]),
                nextCreateRequestId = 1,
            ),
            effects = listOf(
                AiStudioEffect.CreateSession(0, null, "Plan the feature", settings, requestId = 0, isPlanning = true),
            ),
        )
    }

    @Test
    fun `planning is chosen only on an idle new session page`() {
        AiStudioMachineSpec.assertIgnored(
            ready.copy(panes = listOf(StudioPane(0, sessionId = "chat"))),
            AiStudioIntent.Public.SelectPlanning(0, true),
        )
        AiStudioMachineSpec.assertIgnored(
            ready.copy(panes = listOf(StudioPane(0, isCreating = true))),
            AiStudioIntent.Public.SelectPlanning(0, true),
        )
    }

    @Test
    fun `planning and organism modes exclude each other`() {
        val planning = ready.copy(panes = listOf(ready.panes[0].copy(isPlanning = true), ready.panes[1]))
        val organism = ready.copy(panes = listOf(ready.panes[0].copy(isOrganism = true), ready.panes[1]))
        AiStudioMachineSpec.assertTransition(planning, AiStudioIntent.Public.SelectOrganism(0, true), organism)
        AiStudioMachineSpec.assertTransition(organism, AiStudioIntent.Public.SelectPlanning(0, true), planning)
    }

    @Test
    fun `planning keeps an isolated checkout`() {
        val available = ready.copy(isWorktreeAvailable = true, repositoryProjects = setOf("project"), focusedPaneId = 1)
        val worktree = available.copy(panes = listOf(ready.panes[0], ready.panes[1].copy(isWorktree = true)))
        val both = available.copy(
            panes = listOf(ready.panes[0], ready.panes[1].copy(isWorktree = true, isPlanning = true)),
        )
        AiStudioMachineSpec.assertTransition(worktree, AiStudioIntent.Public.SelectPlanning(1, true), both)
        val planning = available.copy(panes = listOf(ready.panes[0], ready.panes[1].copy(isPlanning = true)))
        AiStudioMachineSpec.assertTransition(both, AiStudioIntent.Public.SelectWorktree(1, false), planning)
    }

    @Test
    fun `a new session page forgets planning mode`() {
        val selected = ready.copy(panes = listOf(ready.panes[0].copy(isPlanning = true), ready.panes[1]))
        AiStudioMachineSpec.assertTransition(selected, AiStudioIntent.Public.NewSession(null, 0), ready)
    }
}
