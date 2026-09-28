package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test

class AiStudioProjectsTest {
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0)),
        focusedPaneId = 0,
        settings = RunSettings("codex", ReasoningEffort.High, ApprovalMode.Ask),
        isProjectAddingAvailable = true,
    )

    @Test
    fun `add folder opens only one picker for a new chat`() {
        AiStudioMachineSpec.assertTransition(
            from = ready,
            intent = AiStudioIntent.Public.AddProject(0),
            to = ready.copy(addingProjectTo = 0),
            effects = listOf(AiStudioEffect.ChooseProject(0)),
        )
        listOf(
            ready.copy(isProjectAddingAvailable = false),
            ready.copy(addingProjectTo = 0),
            ready.copy(panes = listOf(StudioPane(0, sessionId = "existing"))),
            ready.copy(panes = listOf(StudioPane(0, isCreating = true))),
        ).forEach { AiStudioMachineSpec.assertIgnored(it, AiStudioIntent.Public.AddProject(0)) }
    }

    @Test
    fun `chosen folder targets the new chat while cancellation preserves its previous selection`() {
        val picking = ready.copy(addingProjectTo = 0)
        AiStudioMachineSpec.assertTransition(
            from = picking,
            intent = AiStudioIntent.Internal.ProjectChosen(0, "opaque-project"),
            to = ready.copy(panes = listOf(StudioPane(0, projectId = "opaque-project"))),
        )
        AiStudioMachineSpec.assertTransition(
            from = picking,
            intent = AiStudioIntent.Internal.ProjectChosen(0, null),
            to = ready,
        )
        AiStudioMachineSpec.assertIgnored(ready, AiStudioIntent.Internal.ProjectChosen(0, "stale-result"))
    }

    @Test
    fun `folder result never rebinds an existing conversation opened while picking`() {
        val switched = ready.copy(panes = listOf(StudioPane(0, sessionId = "existing")))
        AiStudioMachineSpec.assertTransition(
            from = switched.copy(addingProjectTo = 0),
            intent = AiStudioIntent.Internal.ProjectChosen(0, "new-project"),
            to = switched,
        )
    }

    @Test
    fun `failed folder selection is visible and can be retried`() {
        val failed = ready.copy(projectErrorPane = 0)
        AiStudioMachineSpec.assertTransition(
            from = ready.copy(addingProjectTo = 0),
            intent = AiStudioIntent.Internal.ProjectChoiceFailed(0),
            to = failed,
        )
        AiStudioMachineSpec.assertTransition(
            from = failed,
            intent = AiStudioIntent.Public.AddProject(0),
            to = ready.copy(addingProjectTo = 0),
            effects = listOf(AiStudioEffect.ChooseProject(0)),
        )
        AiStudioMachineSpec.assertTransition(
            from = ready,
            intent = AiStudioIntent.Internal.ProjectAvailabilityChanged(false),
            to = ready.copy(isProjectAddingAvailable = false),
        )
    }
}
