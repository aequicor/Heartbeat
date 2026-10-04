package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import kotlin.test.Test

class AiStudioOrganismTest {
    private val settings = RunSettings("route", ReasoningEffort.High, ApprovalMode.Ask)
    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0), StudioPane(1, projectId = "project")),
        focusedPaneId = 0,
        settings = settings,
    )

    @Test
    fun `organism mode is chosen per new pane and passed to creation`() {
        val selected = ready.copy(panes = listOf(ready.panes[0].copy(isOrganism = true), ready.panes[1]))
        AiStudioMachineSpec.assertTransition(ready, AiStudioIntent.Public.SelectOrganism(0, true), selected)
        AiStudioMachineSpec.assertTransition(selected, AiStudioIntent.Public.SelectOrganism(0, false), ready)
        AiStudioMachineSpec.assertTransition(
            selected,
            AiStudioIntent.Public.Submit(0, " Build the thing "),
            selected.copy(
                panes = listOf(selected.panes[0].copy(isCreating = true, createRequestId = 0), selected.panes[1]),
                nextCreateRequestId = 1,
            ),
            effects = listOf(
                AiStudioEffect.CreateSession(0, null, "Build the thing", settings, requestId = 0, isOrganism = true),
            ),
        )
    }

    @Test
    fun `an organism needs a written goal on an idle new pane`() {
        val selected = ready.copy(panes = listOf(ready.panes[0].copy(isOrganism = true), ready.panes[1]))
        val files = listOf(ResourceRef("attachment:image", "image/png"))
        AiStudioMachineSpec.assertIgnored(selected, AiStudioIntent.Public.Submit(0, " ", files))
        AiStudioMachineSpec.assertIgnored(
            ready.copy(panes = listOf(StudioPane(0, sessionId = "chat"))),
            AiStudioIntent.Public.SelectOrganism(0, true),
        )
        AiStudioMachineSpec.assertIgnored(
            ready.copy(panes = listOf(StudioPane(0, isCreating = true))),
            AiStudioIntent.Public.SelectOrganism(0, true),
        )
    }

    @Test
    fun `a new session page forgets the organism mode`() {
        val selected = ready.copy(panes = listOf(ready.panes[0].copy(isOrganism = true), ready.panes[1]))
        AiStudioMachineSpec.assertTransition(selected, AiStudioIntent.Public.NewSession(null, 0), ready)
    }
}
