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
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioPlanImplementerTest {
    private val calls = mutableListOf<String>()
    private val planning = FakePlanning(calls)
    private val host = FakePlanHost(calls)
    private val implementer = StudioPlanImplementer(planning, host)

    @Test
    fun `the same engine switches the current session and keeps the planning context`() = runTest {
        val settings = StudioSessionSettings(
            Pi.copy(model = ModelId("other")).studioModelId(),
            "low",
            ApprovalMode.AutoEdits,
        )
        val outcome = implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings)
        assertEquals(RunOutcome.Completed, outcome)
        assertEquals(
            listOf(
                "validate",
                "configure ${StudioSettingChange.Model(settings.modelId)}",
                "configure ${StudioSettingChange.Effort("low")}",
                "configure ${StudioSettingChange.Approval(ApprovalMode.AutoEdits)}",
                "start 2",
                "run Go",
            ),
            calls,
        )
        assertEquals(ReasoningEffort.Low, host.turnSettings?.effort)
        assertEquals(1, host.reservations)
    }

    @Test
    fun `another engine continues the chat in a new session on the chosen route`() = runTest {
        val settings = StudioSessionSettings(Codex.studioModelId(), null, ApprovalMode.Ask)
        val outcome = implementer.implementPlan(CHAT, PlanImplementation(2, settings, ""), DefaultRunSettings)
        assertEquals(RunOutcome.Completed, outcome)
        assertEquals(
            listOf("validate", "rollover", "start 2", "adopt $Codex", "run Implement the approved plan."),
            calls,
        )
        assertEquals(DefaultRunSettings.effort, host.turnSettings?.effort)
    }

    @Test
    fun `a fresh context rolls over even on the same route`() = runTest {
        val settings = host.chat.configuration!!
        implementer.implementPlan(
            CHAT,
            PlanImplementation(2, settings, "Go", isFreshContext = true),
            DefaultRunSettings,
        )
        assertEquals(listOf("validate", "rollover", "start 2", "adopt $Pi", "run Go"), calls)
    }

    @Test
    fun `a chat without a native session adopts the route directly`() = runTest {
        host.chat = host.chat.copy(ref = null)
        val settings = StudioSessionSettings(Codex.studioModelId())
        implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings)
        assertEquals(listOf("validate", "start 2", "adopt $Codex", "run Go"), calls)
    }

    @Test
    fun `a stale revision or a failed rollover never runs the turn`() = runTest {
        val settings = StudioSessionSettings(Codex.studioModelId())
        assertEquals(
            RunOutcome.Failed,
            implementer.implementPlan(CHAT, PlanImplementation(1, settings, "Go"), DefaultRunSettings),
        )
        assertEquals(emptyList(), calls)
        host.rollover = RunOutcome.Failed
        assertEquals(
            RunOutcome.Failed,
            implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings),
        )
        assertEquals(listOf("validate", "rollover"), calls)
        assertEquals(emptyList(), planning.started)
    }

    @Test
    fun `a refused start after a rollover leaves the chat a planning chat`() = runTest {
        planning.canStart = false
        val settings = StudioSessionSettings(Codex.studioModelId())
        assertEquals(
            RunOutcome.Failed,
            implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings),
        )
        assertEquals(listOf("validate", "rollover", "start 2"), calls)
        assertEquals(Pi.studioModelId(), host.chat.configuration?.modelId)
    }

    @Test
    fun `a refused start undoes the settings applied to the current session`() = runTest {
        planning.canStart = false
        val current = host.chat.configuration!!
        val settings = StudioSessionSettings(
            Pi.copy(model = ModelId("other")).studioModelId(),
            "high",
            current.approval,
        )
        assertEquals(
            RunOutcome.Failed,
            implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings),
        )
        assertEquals(
            listOf(
                "validate",
                "configure ${StudioSettingChange.Model(settings.modelId)}",
                "configure ${StudioSettingChange.Effort("high")}",
                "start 2",
                "configure ${StudioSettingChange.Effort(null)}",
                "configure ${StudioSettingChange.Model(current.modelId)}",
            ),
            calls,
        )
    }

    @Test
    fun `a setting the session refuses undoes the applied ones and keeps planning`() = runTest {
        host.refused += StudioSettingChange.Effort("high")
        val current = host.chat.configuration!!
        val settings = StudioSessionSettings(
            Pi.copy(model = ModelId("other")).studioModelId(),
            "high",
            ApprovalMode.AutoEdits,
        )
        assertEquals(
            RunOutcome.Failed,
            implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings),
        )
        assertEquals(
            listOf(
                "validate",
                "configure ${StudioSettingChange.Model(settings.modelId)}",
                "configure ${StudioSettingChange.Effort("high")}",
                "configure ${StudioSettingChange.Model(current.modelId)}",
            ),
            calls,
        )
        assertEquals(emptyList(), planning.started)
    }

    @Test
    fun `a stop while preparing cancels the implementation and keeps planning`() = runTest {
        host.isStopped = true
        val settings = StudioSessionSettings(Codex.studioModelId())
        assertEquals(
            RunOutcome.Stopped,
            implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings),
        )
        assertEquals(listOf("validate", "rollover"), calls)
        assertEquals(emptyList(), planning.started)
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
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `a refused route is reported as a failure`() = runTest {
        host.validation = IllegalStateException("Choose a model with local project access")
        val settings = StudioSessionSettings(Codex.studioModelId())
        assertEquals(
            RunOutcome.Failed,
            implementer.implementPlan(CHAT, PlanImplementation(2, settings, "Go"), DefaultRunSettings),
        )
        assertEquals(listOf("validate"), calls)
    }

    @Test
    fun `a worktree implementation leaves planning before the chat moves into its checkout`() = runTest {
        host.chat = host.chat.copy(projectId = "project")
        val settings = host.chat.configuration!!
        val request = PlanImplementation(2, settings, "Go", mode = PlanImplementationMode.Sandbox)
        assertEquals(RunOutcome.Completed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(
            listOf(
                "validate checkout",
                "rollover",
                "start 2",
                "move",
                "prepare project sandbox=true",
                "move",
                "adopt $Pi",
                "run Go",
            ),
            calls,
        )
        assertEquals(CHAT, host.chat.worktreeTaskId)
        assertEquals(true, host.chat.isSandbox)
        assertEquals(WorkspaceRef("checkout"), host.chat.executionWorkspace)
    }

    @Test
    fun `a sandbox the journal prepares as a worktree is recorded as a worktree`() = runTest {
        host.chat = host.chat.copy(projectId = "project")
        host.preparedSandbox = false
        val request = PlanImplementation(2, host.chat.configuration!!, "Go", mode = PlanImplementationMode.Sandbox)
        assertEquals(RunOutcome.Completed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(false, host.chat.isSandbox)
    }

    @Test
    fun `a failed checkout keeps the recoverable identity of the chat without running the turn`() = runTest {
        host.chat = host.chat.copy(projectId = "project")
        host.preparation = IllegalStateException("Worktree preparation failed")
        val request = PlanImplementation(2, host.chat.configuration!!, "Go", mode = PlanImplementationMode.Worktree)
        assertEquals(RunOutcome.Failed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(CHAT, host.chat.worktreeTaskId)
        assertNull(host.chat.executionWorkspace)
        assertTrue(host.chat.hasFailed)
        assertFalse(calls.any { it.startsWith("run") })
    }

    @Test
    fun `isolated checkouts that are unavailable refuse before anything changes`() = runTest {
        host.chat = host.chat.copy(projectId = "project")
        host.worktrees = IllegalStateException("Isolated checkouts are unavailable")
        val request = PlanImplementation(2, host.chat.configuration!!, "Go", mode = PlanImplementationMode.Worktree)
        assertEquals(RunOutcome.Failed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(listOf("validate checkout"), calls)
        assertNull(host.chat.worktreeTaskId)
    }

    @Test
    fun `a chat already isolated or without a project is not moved again`() = runTest {
        val settings = host.chat.configuration!!
        val request = PlanImplementation(2, settings, "Go", mode = PlanImplementationMode.Worktree)
        assertEquals(RunOutcome.Failed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(emptyList(), calls)
        host.chat = host.chat.copy(projectId = "project", worktreeTaskId = CHAT)
        assertEquals(RunOutcome.Completed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(listOf("validate", "start 2", "run Go"), calls)
    }

    @Test
    fun `an organism implementation makes the chat its chat with the plan as the goal`() = runTest {
        planning.plan = planning.plan.copy(text = "# Plan\n\n1. Build")
        val settings = host.chat.configuration!!
        val request = PlanImplementation(2, settings, "Go", mode = PlanImplementationMode.Organism)
        assertEquals(RunOutcome.Completed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(listOf("validate", "admit", "rollover", "start 2", "move", "adopt $Pi"), calls.dropLast(1))
        assertEquals(CHAT, host.chat.organismId)
        val turn = calls.last()
        assertTrue(turn.startsWith("run Go\n\nImplement this plan (revision 2)") && "1. Build" in turn, turn)
    }

    @Test
    fun `an organism that is not conceived leaves an ordinary chat`() = runTest {
        host.turn = IllegalStateException("The organism was not conceived")
        val request = PlanImplementation(2, host.chat.configuration!!, "Go", mode = PlanImplementationMode.Organism)
        assertEquals(RunOutcome.Failed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertNull(host.chat.organismId)
        assertEquals(emptySet(), host.chat.organismHarnesses)
    }

    @Test
    fun `an organism cannot implement a plan from an isolated checkout`() = runTest {
        host.chat = host.chat.copy(projectId = "project", worktreeTaskId = CHAT)
        val request = PlanImplementation(2, host.chat.configuration!!, "Go", mode = PlanImplementationMode.Organism)
        assertEquals(RunOutcome.Failed, implementer.implementPlan(CHAT, request, DefaultRunSettings))
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `a long plan is cut to the goal an organism holds`() {
        val plan = planning.plan.copy(text = "x".repeat(OrganismBounds.MAX_GOAL * 2))
        val goal = organismGoal("Go", plan)
        assertEquals(OrganismBounds.MAX_GOAL, goal.length)
        assertTrue("remaining sections were cut" in goal && goal.endsWith("</plan>"))
        assertTrue("<plan>\n# Short\n</plan>" in organismGoal("Go", plan.copy(text = "# Short")))
    }

    @Test
    fun `a long prompt keeps its beginning within the goal an organism holds`() {
        val plan = planning.plan.copy(text = "# Plan")
        val prompt = "😀".repeat(OrganismBounds.MAX_GOAL)
        val goal = organismGoal(prompt, plan)
        assertTrue(goal.length <= OrganismBounds.MAX_GOAL)
        assertTrue(goal.startsWith("😀") && "remaining text was cut" in goal && "<plan>\n# Plan\n" in goal)
        val head = goal.substringBefore("\n\n[")
        assertFalse(head.last().isHighSurrogate())
        assertTrue(head.length <= MAX_GOAL_PROMPT)
        val both = organismGoal(prompt, plan.copy(text = "y".repeat(OrganismBounds.MAX_GOAL)))
        assertTrue(both.length <= OrganismBounds.MAX_GOAL && both.endsWith("</plan>"))
    }

    private class FakePlanning(private val calls: MutableList<String>) : StudioPlanning {
        var plan = Plan(PlanChat(CHAT), PlanMode.Planning, revision = 2, updatedAt = Instant.fromEpochSeconds(0))
        var canStart = true
        val started = mutableListOf<Long>()
        override val availability: Flow<Boolean> = flowOf(true)
        override val plans: Flow<Map<String, Plan>> get() = flowOf(mapOf(CHAT to plan))
        override suspend fun canEnter() = true
        override suspend fun enter(chatId: String) = true
        override suspend fun leave(chatId: String) = true
        override suspend fun bind(chatId: String, session: SessionRef) = Unit
        override suspend fun approve(chatId: String, revision: Long) = true
        override suspend fun write(chatId: String, text: String, base: Long) = true
        override suspend fun startImplementation(chatId: String, revision: Long): Boolean {
            calls += "start $revision"
            if (canStart) started += revision
            return canStart
        }
    }

    private class FakePlanHost(private val calls: MutableList<String>) : StudioPlanHost {
        var chat = StudioChatRecord(
            CHAT,
            "Plan",
            Instant.fromEpochSeconds(0),
            ref = SessionRef(Pi.engine, SessionSourceId("s"), "native"),
            target = Pi,
            configuration = StudioSessionSettings(Pi.studioModelId(), null, ApprovalMode.Ask),
        )
        var rollover = RunOutcome.Completed
        var isStopped = false
        var preparedSandbox: Boolean? = null
        var validation: Exception? = null
        var worktrees: Exception? = null
        var preparation: Exception? = null
        var turn: Exception? = null
        val refused = mutableSetOf<StudioSettingChange>()
        var reservations = 0
        var turnSettings: RunSettings? = null
        private var isReserved = false

        private fun record(call: String) {
            check(isReserved) { "$call ran outside the reservation" }
            calls += call
        }

        override suspend fun planChat(id: String) = chat

        override suspend fun validatePlanTarget(id: String, target: EngineTarget, isCheckoutNeeded: Boolean) {
            record(if (isCheckoutNeeded) "validate checkout" else "validate")
            validation?.let { throw it }
            if (isCheckoutNeeded) worktrees?.let { throw it }
        }

        override suspend fun admitOrganism(
            id: String,
            goal: String,
            settings: RunSettings,
            projectId: String?,
            isWorktree: Boolean,
        ): OrganismAdmission {
            record("admit")
            return OrganismAdmission(id, setOf("harness"))
        }

        override suspend fun reserve(id: String, settings: RunSettings, body: suspend () -> RunOutcome): RunOutcome {
            check(!isReserved) { "Session is busy" }
            reservations++
            isReserved = true
            try {
                return body()
            } finally {
                isReserved = false
            }
        }

        override suspend fun isStopRequested(id: String): Boolean = isStopped

        override suspend fun configure(sessionId: String, change: StudioSettingChange): Boolean {
            record("configure $change")
            return change !in refused
        }

        override suspend fun rolloverForPlan(id: String, settings: RunSettings): RunOutcome {
            record("rollover")
            return rollover
        }

        override suspend fun adoptPlanSettings(id: String, settings: StudioSessionSettings) {
            record("adopt ${studioModelTarget(settings.modelId)}")
        }

        override suspend fun prepareWorktree(id: String, project: String, isSandbox: Boolean): StudioIsolation {
            record("prepare $project sandbox=$isSandbox")
            check(chat.worktreeTaskId == id) { "The chat does not name its checkout before preparing it" }
            preparation?.let { throw it }
            return StudioIsolation(WorkspaceRef("checkout"), preparedSandbox ?: isSandbox)
        }

        override suspend fun move(id: String, change: StudioChatRecord.() -> StudioChatRecord) {
            chat = chat.change()
            record("move")
        }

        override suspend fun runPlanTurn(id: String, prompt: String, settings: RunSettings): RunOutcome {
            record("run $prompt")
            turnSettings = settings
            turn?.let { throw it }
            return RunOutcome.Completed
        }
    }

    private companion object {
        const val CHAT = "chat-1"
        val Pi = EngineTarget(EngineId("pi"), EngineBindingId("pi-key"), ModelId("model"))
        val Codex = EngineTarget(EngineId("codex"), EngineBindingId("codex-login"), ModelId("gpt"))
    }
}
