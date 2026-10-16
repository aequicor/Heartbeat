package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.tools

import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveModels
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsEnabled
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsSessionTools
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ChoiceSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.InheritReason
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelChoice
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Ratings
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionContext
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionOption
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentComplexity
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentHost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentTools
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.FakeToggles
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.runs.SubagentStarts
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.MAX_SUBAGENT_RUNS
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.SubagentRun
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.runs.SubagentRunStore
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.target
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentAvailability
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentRequest
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentStart
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentStatus
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgentWake
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundAgents
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerActions
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SubagentAgentToolsTest {
    private val parent = target("claude", "api", "claude-opus-4-1")
    private val haiku = target("claude", "api", "claude-haiku-4-5")
    private val session = SessionRef(EngineId("claude"), SessionSourceId("local"), "chat")
    private val context = AgentToolContext(
        session,
        WorkspaceRef("project"),
        TurnId("turn"),
        RequestId("request"),
        target = parent,
        decisionMode = AgentToolDecisionMode.Interactive,
    )
    private val spawn = buildJsonObject {
        put(SubagentTools.Arguments.TASK, "Fix the typos in README")
        put(SubagentTools.Arguments.PRIORITY, "speed")
        put(SubagentTools.Arguments.COMPLEXITY, "low")
        put(SubagentTools.Arguments.NEEDS, buildJsonArray { add(JsonPrimitive("images")) })
    }

    @Test
    fun `tools exist only with subagents session tools and scheduler actions on`() = runTest {
        assertTrue(Fixture(backgroundScope, enabled = false).tools.specifications(null).isEmpty())
        val names = Fixture(backgroundScope).tools.specifications(null).map { it.name }
        assertEquals(listOf(SubagentTools.SPAWN, SubagentTools.RECEIVE), names)
    }

    @Test
    fun `a spawn starts a helper on the chosen model reviewed by the parent's model`() = runTest {
        val f = Fixture(backgroundScope)
        val result = f.tools.execute(context, SubagentTools.SPAWN, spawn)
        assertFalse(result.isError, result.text)
        val request = f.agents.started.single()
        assertEquals(haiku, request.target)
        assertEquals(HelperModelOptions("low", reviewTarget = parent), request.model)
        assertEquals(SubagentTools.OWNER, request.owner)
        assertEquals("Fix the typos in README", request.task)
        assertEquals(session, request.parent)
        val selected = f.requests.single()
        assertEquals(
            SubagentRequest(
                "Fix the typos in README",
                SubagentPriority.Speed,
                SubagentComplexity.Low,
                setOf(io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentNeed.Images),
            ),
            selected.first,
        )
        assertEquals(SelectionContext(SubagentHost.StudioHelper, parent, WorkspaceRef("project")), selected.second)
        assertTrue("Haiku" in result.text && "subagent_receive" in result.text, result.text)
        assertEquals(request.id.value, f.runs.list().single().id)
    }

    @Test
    fun `without an adaptive choice the parent's model runs the helper and a refusal is reported`() = runTest {
        val f = Fixture(backgroundScope, choice = ModelChoice.Inherit(InheritReason.Disabled))
        f.tools.execute(context, SubagentTools.SPAWN, spawn)
        assertEquals(parent, f.agents.started.single().target)
        f.agents.refusal = "the profile already runs 8 background actions"
        val refused = f.tools.execute(context, SubagentTools.SPAWN, spawn)
        assertTrue(refused.isError && "8 background actions" in refused.text)
        assertEquals(1, f.runs.list().size)
    }

    @Test
    fun `automatic mode starts only after a safe review of the exact request`() = runTest {
        val automatic = context.copy(decisionMode = AgentToolDecisionMode.AutomaticSafety)
        val f = Fixture(backgroundScope, verdict = AgentSafetyReview("risk", "Exfiltrates keys"))
        val spec = f.tools.specifications(null).first()
        val denied = f.tools.automaticAuthorization(automatic, spec, spawn)
        assertIs<AgentToolAutomaticAuthorization.Denied>(denied)
        assertTrue(f.tools.execute(automatic, SubagentTools.SPAWN, spawn).isError)
        val safe = Fixture(backgroundScope)
        val allowed = assertIs<AgentToolAutomaticAuthorization.Allowed>(
            safe.tools.automaticAuthorization(automatic, spec, spawn),
        )
        assertEquals(safe.tools.approval(automatic, spec, spawn), allowed.approval)
        val authorized = automatic.copy(authorization = allowed.approval)
        assertFalse(safe.tools.execute(authorized, SubagentTools.SPAWN, spawn).isError)
        assertEquals(parent, safe.reviewer.targets.single())
        assertTrue(safe.tools.requiresDecision(context, spec, spawn))
    }

    @Test
    fun `results are read once and a wait sleeps until a running subagent finishes`() = runTest {
        val f = Fixture(backgroundScope)
        f.tools.execute(context, SubagentTools.SPAWN, spawn)
        val id = ActionId(f.runs.list().single().id)
        val waiting = f.tools.execute(context, SubagentTools.RECEIVE, buildJsonObject { put("wait", true) })
        assertTrue("end your turn" in waiting.text, waiting.text)
        val schedule = f.agents.wakes.single()
        assertEquals(setOf(EventKeys.actionFinished(id)), schedule.condition.events)
        assertEquals(session, schedule.session)
        f.agents.statuses[id] = BackgroundAgentStatus.Finished(isCompleted = true, text = "Fixed 3 typos")
        val read = f.tools.execute(context, SubagentTools.RECEIVE, JsonObject(emptyMap()))
        assertTrue("Fixed 3 typos" in read.text && "finished" in read.text, read.text)
        assertEquals(listOf(id), f.agents.acknowledged)
        assertTrue(f.runs.list().isEmpty())
        val other = context.copy(session = session.copy(nativeId = "other"))
        assertTrue("No subagent" in f.tools.execute(other, SubagentTools.RECEIVE, JsonObject(emptyMap())).text)
    }

    @Test
    fun `large result batches acknowledge only complete returned results and fit the tool budget`() = runTest {
        val f = Fixture(backgroundScope)
        val answers = (0 until 20).associate { index ->
            val id = "result-$index"
            val run = SubagentRun(id, session, "t".repeat(200), "model".repeat(100), startedAt = Instant.DISTANT_PAST)
            assertTrue(f.runs.reserve(run))
            val answer = "$id:" + "x".repeat(SchedulerLimits.MAX_PAYLOAD - id.length - 1)
            f.agents.statuses[ActionId(id)] = BackgroundAgentStatus.Finished(true, answer)
            id to answer
        }
        repeat(28) { index ->
            val run = SubagentRun(
                "pending-$index",
                session,
                "t".repeat(200),
                "m".repeat(500),
                startedAt = Instant.DISTANT_PAST,
            )
            assertTrue(f.runs.reserve(run))
            f.agents.statuses[ActionId(run.id)] = BackgroundAgentStatus.Running
        }
        for (count in listOf(8, 8, 4)) {
            val before = f.agents.acknowledged.size
            val read = f.tools.execute(context, SubagentTools.RECEIVE, JsonObject(emptyMap()))
            val delivered = f.agents.acknowledged.drop(before)
            assertEquals(count, delivered.size)
            assertTrue(read.text.length <= ReceiveSpec.resultChars)
            delivered.forEach { assertTrue(answers.getValue(it.value) in read.text) }
            if (count == 8) assertTrue("remain unread" in read.text)
        }
        assertEquals(20, f.agents.acknowledged.toSet().size)
        assertEquals(28, f.runs.list().size)
    }

    @Test
    fun `a full registry refuses the next start without evicting unread results`() = runTest {
        val f = Fixture(backgroundScope)
        repeat(MAX_SUBAGENT_RUNS) { assertFalse(f.tools.execute(context, SubagentTools.SPAWN, spawn).isError) }
        val original = f.runs.list()
        val rejected = f.tools.execute(context, SubagentTools.SPAWN, spawn)
        assertTrue(rejected.isError)
        assertEquals(MAX_SUBAGENT_RUNS, f.agents.started.size)
        assertEquals(original, f.runs.list())
    }

    @Test
    fun `caller cancellation cannot abandon publication or let receive remove a starting run`() = runTest {
        val f = Fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.agents.beforeStart = {
            entered.complete(Unit)
            release.await()
        }
        val caller = async { f.tools.execute(context, SubagentTools.SPAWN, spawn) }
        entered.await()
        val id = ActionId(f.runs.list().single().id)
        caller.cancelAndJoin()
        val pending = f.tools.execute(context, SubagentTools.RECEIVE, JsonObject(emptyMap()))
        assertTrue("Still running" in pending.text)
        assertEquals(id.value, f.runs.list().single().id)
        release.complete(Unit)
        runCurrent()
        assertEquals(id, f.agents.started.single().id)
        f.agents.statuses[id] = BackgroundAgentStatus.Finished(true, "Delivered after caller cancelled")
        val received = f.tools.execute(context, SubagentTools.RECEIVE, JsonObject(emptyMap()))
        assertTrue("Delivered after caller cancelled" in received.text)
        assertTrue(f.runs.list().isEmpty())
    }

    @Test
    fun `reservation failure prevents scheduler admission`() = runTest {
        val f = Fixture(backgroundScope)
        f.runs.reserveFailure = IllegalStateException("Storage unavailable")
        assertFailsWith<IllegalStateException> { f.tools.execute(context, SubagentTools.SPAWN, spawn) }
        assertTrue(f.agents.started.isEmpty())
        assertTrue(f.runs.list().isEmpty())
    }

    @Test
    fun `a failed start keeps its reservation until receive reconciles the scheduler`() = runTest {
        val f = Fixture(backgroundScope)
        f.agents.beforeStart = { error("Host unavailable before admission") }
        assertFailsWith<IllegalStateException> { f.tools.execute(context, SubagentTools.SPAWN, spawn) }
        assertEquals(1, f.runs.list().size)
        val read = f.tools.execute(context, SubagentTools.RECEIVE, JsonObject(emptyMap()))
        assertTrue("Startup was interrupted" in read.text)
        assertTrue(f.runs.list().isEmpty())
    }

    @Test
    fun `a refused wake never instructs the caller to end its turn`() = runTest {
        val f = Fixture(backgroundScope)
        f.tools.execute(context, SubagentTools.SPAWN, spawn)
        f.agents.wake = BackgroundAgentWake.Refused("Pending wake limit reached")
        val read = f.tools.execute(context, SubagentTools.RECEIVE, buildJsonObject { put("wait", true) })
        assertTrue(read.isError)
        assertTrue("Pending wake limit reached" in read.text)
        assertFalse("end your turn" in read.text)
        assertEquals(1, f.runs.list().size)
    }

    private inner class Fixture(
        scope: CoroutineScope,
        enabled: Boolean = true,
        private val choice: ModelChoice = ModelChoice.Selected(
            haiku,
            "low",
            "Haiku",
            Ratings(3, 5, 5),
            "fast",
            ChoiceSource.Scoring,
            1,
        ),
        verdict: AgentSafetyReview = AgentSafetyReview("safe", "Bounded edit"),
    ) {
        // Match ScopeHandleImpl: deferred failures belong to their awaiters, not the profile's parent job.
        private val profileScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val requests = mutableListOf<Pair<SubagentRequest, SelectionContext>>()
        val agents = Agents()
        val runs = MemoryRuns()
        val reviewer = Reviewer(verdict)
        private val adaptive = object : AdaptiveModels {
            override suspend fun select(request: SubagentRequest, context: SelectionContext): ModelChoice {
                requests += request to context
                return choice
            }

            override suspend fun preview(request: SubagentRequest, context: SelectionContext) =
                emptyList<SelectionOption>()
        }
        private val flags = listOf(
            AdaptiveSubagentsEnabled,
            AdaptiveSubagentsSessionTools,
            SchedulerEnabled,
            SchedulerActions,
        )
        val tools = SubagentAgentTools(
            lazyOf(adaptive),
            lazyOf(agents),
            runs,
            SubagentStarts(profileScope, runs, lazyOf(agents)),
            SubagentToolSafety(reviewer),
            FakeToggles(flags.associateWith { enabled }),
            Clock.System,
        )
    }

    private class Agents : BackgroundAgents {
        val started = mutableListOf<BackgroundAgentRequest>()
        val statuses = mutableMapOf<ActionId, BackgroundAgentStatus>()
        val acknowledged = mutableListOf<ActionId>()
        var refusal: String? = null
        var beforeStart: suspend () -> Unit = {}
        val wakes = mutableListOf<WakeRequest>()
        var wake: BackgroundAgentWake = BackgroundAgentWake.Scheduled

        override suspend fun availability(parent: SessionRef) = BackgroundAgentAvailability.Available

        override suspend fun start(request: BackgroundAgentRequest): BackgroundAgentStart {
            beforeStart()
            refusal?.let { return BackgroundAgentStart.Refused(it) }
            started += request
            statuses[request.id] = BackgroundAgentStatus.Running
            return BackgroundAgentStart.Started(request.id)
        }

        override suspend fun schedule(request: WakeRequest): BackgroundAgentWake {
            wakes += request
            return wake
        }

        override suspend fun status(id: ActionId, owner: String) = statuses[id] ?: BackgroundAgentStatus.Unknown
        override suspend fun retained(owner: String) = statuses.keys.toList()
        override suspend fun acknowledge(id: ActionId, owner: String) {
            acknowledged += id
            statuses.remove(id)
        }
    }

    private class MemoryRuns : SubagentRunStore {
        private val runs = mutableListOf<SubagentRun>()
        override suspend fun list() = runs.toList()
        var reserveFailure: Exception? = null
        override suspend fun reserve(run: SubagentRun): Boolean {
            reserveFailure?.let { throw it }
            if (runs.size >= MAX_SUBAGENT_RUNS || runs.any { it.id == run.id }) return false
            runs += run
            return true
        }

        override suspend fun remove(ids: Set<String>) {
            runs.removeAll { it.id in ids }
        }
    }

    private class Reviewer(private val verdict: AgentSafetyReview) : AgentSafetyReviewer {
        val targets = mutableListOf<io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget>()
        override suspend fun review(
            target: io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget,
            policy: String,
            change: String,
        ): AgentSafetyReview {
            targets += target
            return verdict
        }
    }
}
