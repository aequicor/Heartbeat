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
            selected.copy(
                panes = listOf(selected.panes[0].copy(isCreating = true, createRequestId = 0), selected.panes[1]),
                nextCreateRequestId = 1,
            ),
            effects = listOf(
                AiStudioEffect.CreateSession(0, "project", "Implement", settings, requestId = 0, isWorktree = true),
            ),
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
        val selected = ready.copy(
            panes = listOf(StudioPane(0, projectId = "project", createRequestId = 17, isWorktree = true)),
            nextCreateRequestId = 18,
        )
        AiStudioMachineSpec.assertTransition(
            selected.copy(panes = listOf(selected.panes.single().copy(isCreating = true))),
            AiStudioIntent.Internal.CreateFailed(0, "Draft", requestId = 17),
            selected,
            outputs = listOf(AiStudioOutput.SubmitFailed(0, "Draft", requestId = 17)),
        )
    }

    @Test
    fun `abandoned isolated creation cannot start a run in a reused pane`() {
        val creating = ready.copy(
            panes = listOf(
                StudioPane(0, projectId = "project", isCreating = true, createRequestId = 17, isWorktree = true),
            ),
            nextCreateRequestId = 18,
        )
        val replaced = creating.copy(panes = listOf(StudioPane(0, projectId = "other")))
        AiStudioMachineSpec.assertTransition(creating, AiStudioIntent.Public.NewSession("other"), replaced)
        AiStudioMachineSpec.assertIgnored(
            replaced,
            AiStudioIntent.Internal.SessionCreated(0, "isolated-chat", "Implement", settings, requestId = 17),
        )
        AiStudioMachineSpec.assertIgnored(
            replaced,
            AiStudioIntent.Internal.CreateFailed(0, "Implement", requestId = 17),
        )
        AiStudioMachineSpec.assertTransition(
            replaced,
            AiStudioIntent.Public.Submit(0, "Next"),
            replaced.copy(
                panes = listOf(StudioPane(0, projectId = "other", isCreating = true, createRequestId = 18)),
                nextCreateRequestId = 19,
            ),
            effects = listOf(
                AiStudioEffect.CreateSession(0, "other", "Next", settings, requestId = 18, isWorktree = false),
            ),
        )
    }
}
