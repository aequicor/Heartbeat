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
        isPlanningAvailable = true,
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
    fun `planning is offered only while the feature is available`() {
        val unavailable = ready.copy(isPlanningAvailable = false)
        AiStudioMachineSpec.assertIgnored(unavailable, AiStudioIntent.Public.SelectPlanning(0, true))
        val stale = unavailable.copy(panes = listOf(ready.panes[0].copy(isPlanning = true), ready.panes[1]))
        AiStudioMachineSpec.assertTransition(stale, AiStudioIntent.Public.SelectPlanning(0, false), unavailable)
        AiStudioMachineSpec.assertTransition(
            stale,
            AiStudioIntent.Public.Submit(0, "Do it"),
            stale.copy(
                panes = listOf(stale.panes[0].copy(isCreating = true, createRequestId = 0), stale.panes[1]),
                nextCreateRequestId = 1,
            ),
            effects = listOf(AiStudioEffect.CreateSession(0, null, "Do it", settings, requestId = 0)),
        )
    }

    @Test
    fun `losing planning clears it on idle new panes only`() {
        val planning = ready.panes[0].copy(isPlanning = true)
        val creating = StudioPane(1, isCreating = true, createRequestId = 0, isPlanning = true)
        val from = ready.copy(panes = listOf(planning, creating))
        AiStudioMachineSpec.assertTransition(
            from,
            AiStudioIntent.Internal.PlanningAvailabilityChanged(false),
            from.copy(panes = listOf(ready.panes[0], creating), isPlanningAvailable = false),
        )
        val off = ready.copy(isPlanningAvailable = false)
        AiStudioMachineSpec.assertTransition(off, AiStudioIntent.Internal.PlanningAvailabilityChanged(true), ready)
    }

    @Test
    fun `a failed creation keeps planning only while it is available`() {
        val creating = ready.copy(
            panes = listOf(StudioPane(0, isCreating = true, createRequestId = 0, isPlanning = true), ready.panes[1]),
            nextCreateRequestId = 1,
        )
        val failed = AiStudioIntent.Internal.CreateFailed(0, "Plan", 0)
        AiStudioMachineSpec.assertTransition(
            creating,
            failed,
            creating.copy(panes = listOf(StudioPane(0, createRequestId = 0, isPlanning = true), ready.panes[1])),
            outputs = listOf(AiStudioOutput.SubmitFailed(0, "Plan", 0)),
        )
        val gone = creating.copy(isPlanningAvailable = false)
        AiStudioMachineSpec.assertTransition(
            gone,
            failed,
            gone.copy(panes = listOf(StudioPane(0, createRequestId = 0), ready.panes[1])),
            outputs = listOf(AiStudioOutput.SubmitFailed(0, "Plan", 0)),
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

    @Test
    fun `implementing a plan runs the shown idle chat`() {
        val shown = ready.copy(panes = listOf(StudioPane(0, sessionId = "chat")))
        val implementation = PlanImplementation(3, StudioSessionSettings("model", "high"), "Go")
        AiStudioMachineSpec.assertTransition(
            shown,
            AiStudioIntent.Public.ImplementPlan("chat", implementation),
            shown.copy(running = setOf("chat")),
            effects = listOf(AiStudioEffect.ImplementPlan("chat", implementation, settings)),
        )
        AiStudioMachineSpec.assertIgnored(
            shown.copy(running = setOf("chat")),
            AiStudioIntent.Public.ImplementPlan("chat", implementation),
        )
        AiStudioMachineSpec.assertIgnored(ready, AiStudioIntent.Public.ImplementPlan("chat", implementation))
        AiStudioMachineSpec.assertIgnored(
            shown.copy(
                configurations = mapOf(
                    "chat" to StudioSessionConfiguration(StudioSessionSettings("model"), pendingOperation = "op"),
                ),
            ),
            AiStudioIntent.Public.ImplementPlan("chat", implementation),
        )
    }
}
