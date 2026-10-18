package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.PlanImplementation
import io.aequicor.heartbeat.feature.aistudio.api.PlanImplementationMode
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanning
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioPlanImplementerTest {
    private val planning = FakePlanning()
    private val host = FakePlanHost()
    private val implementer = StudioPlanImplementer(planning, host)

    @Test
    fun `the same engine switches the current session and keeps the planning context`() = runTest {
        val settings = StudioSessionSettings(
            Pi.copy(model = ModelId("other")).studioModelId(),
            "high",
            ApprovalMode.AutoEdits,
        )
        val outcome = implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings)
        assertEquals(RunOutcome.Completed, outcome)
        assertEquals(
            listOf(
                "validate",
                "configure ${StudioSettingChange.Model(settings.modelId)}",
                "configure ${StudioSettingChange.Effort("high")}",
                "configure ${StudioSettingChange.Approval(ApprovalMode.AutoEdits)}",
                "run Go",
            ),
            host.calls,
        )
        assertEquals(listOf(2L), planning.started)
    }

    @Test
    fun `another engine continues the chat in a new session on the chosen route`() = runTest {
        val settings = StudioSessionSettings(Codex.studioModelId(), null, ApprovalMode.Ask)
        val outcome = implementer.implementPlan(CHAT, PlanImplementation(2, settings, ""), DefaultRunSettings)
        assertEquals(RunOutcome.Completed, outcome)
        assertEquals(listOf("validate", "rollover", "adopt $Codex", "run Implement the approved plan."), host.calls)
    }

    @Test
    fun `a fresh context rolls over even on the same route`() = runTest {
        val settings = host.chat.configuration!!
        implementer.implementPlan(
            CHAT,
            PlanImplementation(2, settings, "Go", isFreshContext = true),
            DefaultRunSettings,
        )
        assertEquals(listOf("validate", "rollover", "adopt $Pi", "run Go"), host.calls)
    }

    @Test
    fun `a chat without a native session adopts the route directly`() = runTest {
        host.chat = host.chat.copy(ref = null)
        val settings = StudioSessionSettings(Codex.studioModelId())
        implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings)
        assertEquals(listOf("validate", "adopt $Codex", "run Go"), host.calls)
    }

    @Test
    fun `a stale revision a failed rollover or a refused start never runs the turn`() = runTest {
        val settings = StudioSessionSettings(Codex.studioModelId())
        assertEquals(
            RunOutcome.Failed,
            implementer.implementPlan(CHAT, PlanImplementation(1, settings, "Go"), DefaultRunSettings),
        )
        assertEquals(emptyList(), host.calls)
        host.rollover = RunOutcome.Failed
        assertEquals(
            RunOutcome.Failed,
            implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings),
        )
        assertEquals(listOf("validate", "rollover"), host.calls)
        assertEquals(emptyList(), planning.started)
        host.calls.clear()
        host.rollover = RunOutcome.Completed
        planning.canStart = false
        assertEquals(
            RunOutcome.Failed,
            implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings),
        )
        assertEquals(listOf("validate", "rollover", "adopt $Codex"), host.calls)
    }

    @Test
    fun `an implementing plan or an organism chat is not implemented again`() = runTest {
        val settings = StudioSessionSettings(Codex.studioModelId())
        planning.plan = planning.plan.copy(mode = PlanMode.Implementing)
        assertEquals(
            RunOutcome.Failed,
            implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings),
        )
        planning.plan = planning.plan.copy(mode = PlanMode.Planning)
        host.chat = host.chat.copy(organismId = CHAT)
        assertEquals(
            RunOutcome.Failed,
            implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings),
        )
        assertEquals(emptyList(), host.calls)
    }

    @Test
    fun `a worktree implementation prepares the checkout and continues the chat there`() = runTest {
        host.chat = host.chat.copy(projectId = "project")
        val settings = host.chat.configuration!!
        val request = PlanImplementation(2, settings, "Go", mode = PlanImplementationMode.Sandbox)
        assertEquals(RunOutcome.Completed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(
            listOf(
                "validate",
                "prepare project sandbox=true",
                "rollover",
                "move",
                "adopt $Pi",
                "run Go",
            ),
            host.calls,
        )
    }

    @Test
    fun `a chat already isolated or without a project is not moved again`() = runTest {
        val settings = host.chat.configuration!!
        val request = PlanImplementation(2, settings, "Go", mode = PlanImplementationMode.Worktree)
        assertEquals(RunOutcome.Failed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(emptyList(), host.calls)
        host.chat = host.chat.copy(projectId = "project", worktreeTaskId = CHAT)
        assertEquals(RunOutcome.Completed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(listOf("validate", "run Go"), host.calls)
    }

    @Test
    fun `a worktree move records the checkout in the chat`() = runTest {
        host.chat = host.chat.copy(projectId = "project")
        val request = PlanImplementation(2, host.chat.configuration!!, "Go", mode = PlanImplementationMode.Worktree)
        implementer.implementPlan(CHAT, request, DefaultRunSettings)
        assertEquals(CHAT, host.chat.worktreeTaskId)
        assertEquals(false, host.chat.isSandbox)
        assertEquals(WorkspaceRef("checkout"), host.chat.executionWorkspace)
    }

    @Test
    fun `an organism implementation makes the chat its chat with the plan as the goal`() = runTest {
        planning.plan = planning.plan.copy(text = "# Plan\n\n1. Build")
        val settings = host.chat.configuration!!
        val request = PlanImplementation(2, settings, "Go", mode = PlanImplementationMode.Organism)
        assertEquals(RunOutcome.Completed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(listOf("validate", "admit", "rollover", "move", "adopt $Pi"), host.calls.dropLast(1))
        assertEquals(CHAT, host.chat.organismId)
        val turn = host.calls.last()
        assertTrue(turn.startsWith("run Go\n\nImplement this plan (revision 2)") && "1. Build" in turn, turn)
    }

    @Test
    fun `an organism cannot implement a plan from an isolated checkout`() = runTest {
        host.chat = host.chat.copy(projectId = "project", worktreeTaskId = CHAT)
        val request = PlanImplementation(2, host.chat.configuration!!, "Go", mode = PlanImplementationMode.Organism)
        assertEquals(RunOutcome.Failed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(emptyList(), host.calls)
    }

    @Test
    fun `a long plan is cut to the goal an organism holds`() {
        val plan = planning.plan.copy(text = "x".repeat(OrganismBounds.MAX_GOAL * 2))
        val goal = organismGoal("Go", plan)
        assertEquals(OrganismBounds.MAX_GOAL, goal.length)
        assertTrue("remaining sections were cut" in goal && goal.endsWith("</plan>"))
        assertTrue("<plan>\n# Short\n</plan>" in organismGoal("Go", plan.copy(text = "# Short")))
    }

    private class FakePlanning : StudioPlanning {
        var plan = Plan(PlanChat(CHAT), PlanMode.Planning, revision = 2, updatedAt = Instant.fromEpochSeconds(0))
        var canStart = true
        val started = mutableListOf<Long>()
        override val availability: Flow<Boolean> = flowOf(true)
        override val plans: Flow<Map<String, Plan>> get() = flowOf(mapOf(CHAT to plan))
        override suspend fun enter(chatId: String) = true
        override suspend fun leave(chatId: String) = true
        override suspend fun bind(chatId: String, session: SessionRef) = Unit
        override suspend fun startImplementation(chatId: String, revision: Long): Boolean {
            if (canStart) started += revision
            return canStart
        }
    }

    private class FakePlanHost : StudioPlanHost {
        var chat = StudioChatRecord(
            CHAT,
            "Plan",
            Instant.fromEpochSeconds(0),
            ref = SessionRef(Pi.engine, SessionSourceId("s"), "native"),
            target = Pi,
            configuration = StudioSessionSettings(Pi.studioModelId(), null, ApprovalMode.Ask),
        )
        var rollover = RunOutcome.Completed
        val calls = mutableListOf<String>()
        override suspend fun planChat(id: String) = chat
        override suspend fun validatePlanTarget(id: String, target: EngineTarget) {
            calls += "validate"
        }
        override suspend fun configure(sessionId: String, change: StudioSettingChange): Boolean {
            calls += "configure $change"
            return true
        }
        override suspend fun rolloverForPlan(id: String, settings: RunSettings): RunOutcome {
            calls += "rollover"
            return rollover
        }
        override suspend fun adoptPlanSettings(id: String, settings: StudioSessionSettings) {
            calls += "adopt ${studioModelTarget(settings.modelId)}"
        }
        override suspend fun prepareWorktree(id: String, project: String, isSandbox: Boolean): WorkspaceRef {
            calls += "prepare $project sandbox=$isSandbox"
            return WorkspaceRef("checkout")
        }

        override suspend fun admitOrganism(
            id: String,
            goal: String,
            settings: RunSettings,
            projectId: String?,
        ): OrganismAdmission {
            calls += "admit"
            return OrganismAdmission(id, emptySet())
        }

        override suspend fun move(id: String, change: StudioChatRecord.() -> StudioChatRecord) {
            chat = chat.change()
            calls += "move"
        }

        override suspend fun runPlanTurn(id: String, prompt: String, settings: RunSettings): RunOutcome {
            calls += "run $prompt"
            return RunOutcome.Completed
        }
    }

    private companion object {
        const val CHAT = "chat-1"
        val Pi = EngineTarget(EngineId("pi"), EngineBindingId("pi-key"), ModelId("model"))
        val Codex = EngineTarget(EngineId("codex"), EngineBindingId("codex-login"), ModelId("gpt"))
    }
}
