package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote.isAllowedRemotely
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanAuthorUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanUi
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioPlanViewTest {
    private val plan = Plan(
        PlanChat("chat"),
        PlanMode.Planning,
        PlanStatus.Proposed,
        revision = 4,
        text = "# Feature\n\nSteps",
        updatedAt = Instant.fromEpochSeconds(0),
        updatedBy = PlanAuthor.Agent,
    )

    @Test
    fun `an attached plan is shown with its title and revision`() {
        assertEquals(
            PlanUi(4, isPlanning = true, PlanStatusUi.Proposed, "Feature", "# Feature\n\nSteps", PlanAuthorUi.Agent),
            plan.toUi(),
        )
        assertEquals(false, plan.copy(mode = PlanMode.Implementing).toUi()?.isPlanning)
        assertNull(plan.copy(mode = PlanMode.Detached).toUi())
    }

    @Test
    fun `plan intents work from remote views and hide the text`() {
        val intents = listOf(
            AiStudioScreenIntent.SelectPlanning(0, true),
            AiStudioScreenIntent.SetPlanning("chat", true),
            AiStudioScreenIntent.ApprovePlan("chat", 4),
            AiStudioScreenIntent.SavePlan("chat", 4, "secret"),
            AiStudioScreenIntent.ImplementPlan("chat", 4, "model", null, ApprovalUi.Ask, prompt = "secret"),
        )
        intents.forEach { assertTrue(isAllowedRemotely(it), it.toString()) }
        intents.forEach { assertTrue("secret" !in it.toString(), it.toString()) }
        assertTrue("Steps" !in plan.toUi().toString())
    }
}
