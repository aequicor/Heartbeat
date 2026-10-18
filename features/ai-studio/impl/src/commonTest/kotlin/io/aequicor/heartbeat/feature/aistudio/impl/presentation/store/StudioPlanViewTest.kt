package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.PlanImplementationMode
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanning
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote.isAllowedRemotely
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanAuthorUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanImplementationModeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PlanUi
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private val ready = AiStudioState.Ready(
        panes = listOf(StudioPane(0, sessionId = "chat"), StudioPane(1)),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
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

    @Test
    fun `only attached plans of shown chats reach the screen`() = runTest {
        val planning = RecordingPlanning()
        planning.plans.value = mapOf(
            "chat" to plan,
            "hidden" to plan.copy(chat = PlanChat("hidden")),
        )
        val fixture = AiStudioModelTest.Fixture(this, ready, planning = planning)
        val screen = fixture.subscribe()
        runCurrent()
        assertEquals(setOf("chat"), screen.states.value.plans.keys)

        planning.plans.value = mapOf("chat" to plan.copy(mode = PlanMode.Detached))
        runCurrent()
        assertTrue(screen.states.value.plans.isEmpty())
    }

    @Test
    fun `planning is offered exactly when the studio machine offers it`() = runTest {
        val planning = RecordingPlanning()
        val fixture = AiStudioModelTest.Fixture(this, ready.copy(isPlanningAvailable = true), planning = planning)
        val screen = fixture.subscribe()
        runCurrent()
        assertTrue(screen.states.value.isPlanningEnabled)

        // The planning port still offers planning: the machine (demo space, failed observation) is the only source.
        fixture.machine.state.value = ready.copy(isPlanningAvailable = false)
        runCurrent()
        assertFalse(screen.states.value.isPlanningEnabled)

        fixture.machine.state.value = AiStudioState.Loading
        runCurrent()
        assertFalse(screen.states.value.isPlanningEnabled)
    }

    @Test
    fun `plan commands go to the planning feature and the studio machine`() = runTest {
        val planning = RecordingPlanning()
        val fixture = AiStudioModelTest.Fixture(this, ready, planning = planning)
        val screen = fixture.subscribe()
        with(screen) {
            intent(AiStudioScreenIntent.SelectPlanning(1, true))
            intent(AiStudioScreenIntent.SetPlanning("chat", true))
            intent(AiStudioScreenIntent.SetPlanning("chat", false))
            intent(AiStudioScreenIntent.ApprovePlan("chat", 4))
            intent(AiStudioScreenIntent.SavePlan("chat", 4, "text"))
            intent(
                AiStudioScreenIntent.ImplementPlan(
                    "chat",
                    4,
                    "model",
                    null,
                    ApprovalUi.Ask,
                    PlanImplementationModeUi.Worktree,
                ),
            )
        }
        runCurrent()
        assertEquals(listOf("enter chat", "leave chat", "approve chat 4", "write chat 4 text"), planning.calls)
        assertTrue(AiStudioIntent.Public.SelectPlanning(1, true) in fixture.machine.sent)
        val implement = fixture.machine.sent.filterIsInstance<AiStudioIntent.Public.ImplementPlan>().single()
        assertEquals("chat", implement.sessionId)
        assertEquals(PlanImplementationMode.Worktree, implement.implementation.mode)
        assertEquals(4, implement.implementation.revision)
    }

    @Test
    fun `a refused plan command changes nothing on the screen`() = runTest {
        val planning = RecordingPlanning(isAccepted = false)
        planning.plans.value = mapOf("chat" to plan)
        val fixture = AiStudioModelTest.Fixture(this, ready, planning = planning)
        val screen = fixture.subscribe()
        runCurrent()
        val before = screen.states.value.plans
        screen.intent(AiStudioScreenIntent.ApprovePlan("chat", 3))
        runCurrent()
        assertEquals(listOf("approve chat 3"), planning.calls)
        assertEquals(before, screen.states.value.plans)
    }

    private class RecordingPlanning(private val isAccepted: Boolean = true) : StudioPlanning {
        val calls = mutableListOf<String>()
        override val availability = MutableStateFlow(true)
        override val plans = MutableStateFlow<Map<String, Plan>>(emptyMap())

        override suspend fun canEnter(): Boolean = isAccepted

        override suspend fun enter(chatId: String): Boolean = record("enter $chatId")

        override suspend fun leave(chatId: String): Boolean = record("leave $chatId")

        override suspend fun bind(chatId: String, session: SessionRef) = Unit

        override suspend fun startImplementation(chatId: String, revision: Long): Boolean =
            record("implement $chatId $revision")

        override suspend fun approve(chatId: String, revision: Long): Boolean = record("approve $chatId $revision")

        override suspend fun write(chatId: String, text: String, base: Long): Boolean =
            record("write $chatId $base $text")

        private fun record(call: String): Boolean {
            calls += call
            return isAccepted
        }
    }
}
