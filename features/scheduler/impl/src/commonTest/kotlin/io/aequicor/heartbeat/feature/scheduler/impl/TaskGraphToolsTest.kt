package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskRun
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphState
import io.aequicor.heartbeat.feature.scheduler.impl.data.TaskGraphToolSpecs
import io.aequicor.heartbeat.feature.scheduler.impl.data.TaskGraphTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskGraphToolsTest {
    private val context = AgentToolContext(SESSION, PROJECT, TurnId("turn"), target = TARGET)
    private fun args(text: String) = Json.parseToJsonElement(text) as JsonObject
    private val create =
        args(
            """{"graph_id":"graph","tasks":[
        {"id":"A","action":{"kind":"command","command":"A"}},
        {"id":"B","action":{"kind":"agent","prompt":"B"},"dependencies":{
          "kind":"all","tasks":[{"task":"A"}]}}]}""",
        )

    @Test
    fun `every declared graph tool is in the catalog so policy can turn it off`() = runTest {
        val f = GraphFixture(this)
        val tools = TaskGraphTools(f.machine, f.driver, safetyOf())
        val catalog = tools.catalog.map { it.name }.toSet()
        assertEquals(TaskGraphToolSpecs.all.map { it.name }.toSet(), catalog)
        assertTrue(tools.specifications(PROJECT).all { it.name in catalog })
        assertEquals("scheduler", tools.group)
    }

    @Test
    fun `causal metadata does not change the literal legacy approval binding`() = runTest {
        val f = GraphFixture(this)
        val tools = TaskGraphTools(f.machine, f.driver, safetyOf())
        val spec = tools.specifications(PROJECT).first { it.name == TaskGraphToolSpecs.CREATE }
        val arguments = args(
            """{"graph_id":"graph","tasks":[{"id":"A","action":{"kind":"command","command":"A"}}]}""",
        )
        val approval = tools.approval(
            context.copy(workspace = null, target = null, request = RequestId("new-R")),
            spec,
            arguments,
        )
        val legacy = """
            {"id":"graph","owner":{"engine":{"value":"pi"},"source":{"value":"local"},"nativeId":"native-1"},
            "workspace":null,"target":null,"definition":{"tasks":[{"id":"A",
            "action":{"kind":"command","command":"A","timeoutSeconds":1800},
            "dependencies":{"kind":"all","tasks":[]}}]},"approval":"","runs":{},
            "isCancelled":false,"isCompletionNotified":false}
        """.trimIndent().replace("\n", "")
        assertEquals(legacy, approval.binding)
    }

    @Test
    fun `creation keeps its first exact initiator across idempotent retries from another request`() = runTest {
        val f = GraphFixture(this)
        val tools = TaskGraphTools(f.machine, f.driver, safetyOf())
        val spec = tools.specifications(PROJECT).first { it.name == TaskGraphToolSpecs.CREATE }
        val first = context.copy(request = RequestId("first"))
        val approval = tools.approval(first, spec, create)
        assertFalse(tools.execute(first.copy(authorization = approval), spec.name, create).isError)
        val retry = context.copy(request = RequestId("retry"), authorization = approval)
        assertEquals(approval, tools.approval(retry, spec, create))
        assertFalse(tools.execute(retry, spec.name, create).isError)
        assertEquals(RequestInitiator(SESSION, RequestId("first")), f.machine.graph().initiator)
        assertTrue(f.machine.graph().causes.isEmpty())
        val cancel = tools.specifications(PROJECT).first { it.name == TaskGraphToolSpecs.CANCEL }
        val arguments = args("""{"graph_id":"graph"}""")
        val authorized = retry.copy(authorization = tools.existingAuthorization(retry, cancel, arguments))
        assertFalse(tools.execute(authorized, cancel.name, arguments).isError)
        assertEquals(setOf(RequestInitiator(SESSION, RequestId("retry"))), f.machine.graph().causes)
    }

    @Test
    fun `creation binds full graph to the owner and is idempotent after lost response`() = runTest {
        val f = GraphFixture(this)
        val tools = TaskGraphTools(f.machine, f.driver, safetyOf())
        val spec = tools.specifications(PROJECT).first { it.name == TaskGraphToolSpecs.CREATE }
        assertTrue(tools.requiresDecision(context, spec, create))
        assertNull(tools.existingAuthorization(context, spec, create))
        assertTrue(tools.execute(context, spec.name, create).isError)
        val approval = tools.approval(context, spec, create)
        assertTrue(approval.description.orEmpty().contains("dependencies"))
        val approved = context.copy(authorization = approval)
        assertFalse(tools.execute(approved, spec.name, create).isError)
        assertFalse(tools.execute(approved, spec.name, create).isError)
        assertEquals(1, (f.machine.state.value as TaskGraphState.Ready).graphs.size)
        val changed = args(create.toString().replace("\"command\":\"A\"", "\"command\":\"different\""))
        assertTrue(tools.execute(approved, spec.name, changed).isError)
        val foreign = approved.copy(session = OTHER)
        assertTrue(tools.execute(foreign, spec.name, create).isError)
        assertTrue(tools.execute(foreign, TaskGraphToolSpecs.GET, args("""{"graph_id":"graph"}""")).isError)
    }

    @Test
    fun `only exact interrupted attempt receives prior graph authorization`() = runTest {
        val f = GraphFixture(this)
        val graph = f.graph(listOf(f.command("A"))).copy(
            runs = mapOf(
                "A" to GraphTaskRun(
                    phase = GraphTaskPhase.RecoveryRequired,
                    execution = "old",
                    attempt = 1,
                ),
            ),
        )
        f.machine.state.value = TaskGraphState.Ready(listOf(graph), persisted = 0)
        val tools = TaskGraphTools(f.machine, f.driver, safetyOf())
        val spec = tools.specifications(PROJECT).first { it.name == TaskGraphToolSpecs.RESOLVE }
        val decision = args(
            """{"graph_id":"graph","task_id":"A","execution":"old","decision":"retry","explanation":"checked"}""",
        )
        val retryContext = context.copy(request = RequestId("resolve-R"))
        val approval = assertNotNull(tools.existingAuthorization(retryContext, spec, decision))
        assertNull(tools.existingAuthorization(context.copy(session = OTHER), spec, decision))
        assertFalse(tools.execute(retryContext.copy(authorization = approval), spec.name, decision).isError)
        assertEquals(setOf(RequestInitiator(SESSION, RequestId("resolve-R"))), f.machine.graph().causes)
        assertNull(tools.existingAuthorization(context, spec, decision))
        assertTrue(tools.execute(retryContext.copy(authorization = approval), spec.name, decision).isError)
    }

    private fun GraphFixture.reviewedTools(reviewer: RecordingReviewer = RecordingReviewer()) =
        TaskGraphTools(machine, driver, safetyOf(reviewer))

    @Test
    fun `automatic mode reviews the whole graph and keeps the plain graph as its saved approval`() = runTest {
        val f = GraphFixture(this)
        val reviewer = RecordingReviewer()
        val tools = f.reviewedTools(reviewer)
        val spec = tools.specifications(PROJECT).first { it.name == TaskGraphToolSpecs.CREATE }
        val automatic = context.automatic()
        val allowed = assertIs<AgentToolAutomaticAuthorization.Allowed>(
            tools.automaticAuthorization(automatic, spec, create),
        )
        assertEquals(tools.approval(automatic, spec, create), allowed.approval)
        assertEquals(tools.approval(context, spec, create), allowed.approval)
        assertEquals(1, reviewer.changes.size)
        val change = reviewer.changes.single()
        assertTrue("scheduler_create_graph" in change && "\"prompt\":\"B\"" in change, change)
        val authorized = automatic.copy(authorization = allowed.approval)
        assertFalse(tools.execute(authorized, spec.name, create).isError)
        assertEquals(allowed.approval.binding, f.machine.graph().approval)
        assertFalse(tools.execute(authorized, spec.name, create).isError)
        assertEquals(1, (f.machine.state.value as TaskGraphState.Ready).graphs.size)
        assertEquals(1, reviewer.changes.size)
    }

    @Test
    fun `automatic mode refuses a graph the reviewer does not call safe or cannot judge`() = runTest {
        val f = GraphFixture(this)
        val reviewer = RecordingReviewer(AgentSafetyReview("risk", "Deletes the home directory"))
        val tools = f.reviewedTools(reviewer)
        val spec = tools.specifications(PROJECT).first { it.name == TaskGraphToolSpecs.CREATE }
        val denied = assertIs<AgentToolAutomaticAuthorization.Denied>(
            tools.automaticAuthorization(context.automatic(), spec, create),
        )
        assertTrue("Deletes the home directory" in denied.reason, denied.reason)
        reviewer.failure = IllegalStateException("model unavailable")
        val unavailable = assertIs<AgentToolAutomaticAuthorization.Denied>(
            tools.automaticAuthorization(context.automatic("again"), spec, create),
        )
        assertTrue("unavailable" in unavailable.reason, unavailable.reason)
        reviewer.failure = null
        reviewer.verdict = AgentSafetyReview("safe", "Bounded")
        val unknownModel = context.automatic("model").copy(target = null)
        assertIs<AgentToolAutomaticAuthorization.Denied>(tools.automaticAuthorization(unknownModel, spec, create))
        assertTrue((f.machine.state.value as TaskGraphState.Ready).graphs.isEmpty())
    }

    @Test
    fun `a graph creation cannot run is refused before it costs a review`() = runTest {
        val f = GraphFixture(this)
        val reviewer = RecordingReviewer()
        val tools = f.reviewedTools(reviewer)
        val spec = tools.specifications(PROJECT).first { it.name == TaskGraphToolSpecs.CREATE }
        val noProject = assertIs<AgentToolAutomaticAuthorization.Denied>(
            tools.automaticAuthorization(context.automatic().copy(workspace = null), spec, create),
        )
        assertEquals("Command tasks require a Desktop project", noProject.reason)
        f.host.isAvailable = false
        val noHost = assertIs<AgentToolAutomaticAuthorization.Denied>(
            tools.automaticAuthorization(context.automatic(), spec, create),
        )
        assertEquals("No graph-capable chat host owns this session", noHost.reason)
        assertTrue(reviewer.changes.isEmpty())
    }

    @Test
    fun `a graph verdict is consumed once and covers neither another call nor another caller`() = runTest {
        val f = GraphFixture(this)
        val tools = f.reviewedTools()
        val spec = tools.specifications(PROJECT).first { it.name == TaskGraphToolSpecs.CREATE }
        val automatic = context.automatic()
        val unreviewed = automatic.copy(authorization = tools.approval(automatic, spec, create))
        val missing = tools.execute(unreviewed, spec.name, create)
        assertTrue(missing.isError && "expired" in missing.text, missing.text)
        tools.automaticAuthorization(automatic, spec, create)
        for (foreign in listOf(automatic.automatic("other-call"), automatic.copy(request = RequestId("other")))) {
            val result = tools.execute(foreign.copy(authorization = unreviewed.authorization), spec.name, create)
            assertTrue(result.isError && "expired" in result.text, result.text)
        }
        assertTrue((f.machine.state.value as TaskGraphState.Ready).graphs.isEmpty())
        assertFalse(tools.execute(unreviewed, spec.name, create).isError)
        assertEquals(1, (f.machine.state.value as TaskGraphState.Ready).graphs.size)
    }

    @Test
    fun `interactive callers and graph reads never reach the reviewer`() = runTest {
        val f = GraphFixture(this)
        val reviewer = RecordingReviewer()
        val tools = f.reviewedTools(reviewer)
        val specs = tools.specifications(PROJECT).associateBy { it.name }
        val ids = args("""{"graph_id":"graph"}""")
        assertNull(tools.automaticAuthorization(context, specs.getValue(TaskGraphToolSpecs.CREATE), create))
        assertNull(tools.automaticAuthorization(context, specs.getValue(TaskGraphToolSpecs.CANCEL), ids))
        assertNull(tools.automaticAuthorization(context.automatic(), specs.getValue(TaskGraphToolSpecs.LIST), ids))
        assertNull(tools.automaticAuthorization(context.automatic(), specs.getValue(TaskGraphToolSpecs.GET), ids))
        assertTrue(reviewer.changes.isEmpty())
    }

    @Test
    fun `automatic cancellation and recovery reuse the saved graph authorization without a review`() = runTest {
        val f = GraphFixture(this)
        val graph = f.graph(listOf(f.command("A"))).copy(
            runs = mapOf("A" to GraphTaskRun(phase = GraphTaskPhase.RecoveryRequired, execution = "old", attempt = 1)),
        )
        f.machine.state.value = TaskGraphState.Ready(listOf(graph), persisted = 0)
        val reviewer = RecordingReviewer(AgentSafetyReview("risk", "Would refuse everything"))
        val tools = f.reviewedTools(reviewer)
        val specs = tools.specifications(PROJECT).associateBy { it.name }
        val automatic = context.automatic()
        val resolve = specs.getValue(TaskGraphToolSpecs.RESOLVE)
        val decision = args(
            """{"graph_id":"graph","task_id":"A","execution":"old","decision":"retry","explanation":"checked"}""",
        )
        val allowed = assertIs<AgentToolAutomaticAuthorization.Allowed>(
            tools.automaticAuthorization(automatic, resolve, decision),
        )
        assertEquals(tools.existingAuthorization(automatic, resolve, decision), allowed.approval)
        assertFalse(tools.execute(automatic.copy(authorization = allowed.approval), resolve.name, decision).isError)
        val stale = decision.toString().replace("\"old\"", "\"older\"")
        assertIs<AgentToolAutomaticAuthorization.Denied>(
            tools.automaticAuthorization(automatic, resolve, args(stale)),
        )
        val cancel = specs.getValue(TaskGraphToolSpecs.CANCEL)
        val ids = args("""{"graph_id":"graph"}""")
        val foreign = automatic.copy(session = OTHER)
        assertIs<AgentToolAutomaticAuthorization.Denied>(tools.automaticAuthorization(foreign, cancel, ids))
        assertIs<AgentToolAutomaticAuthorization.Denied>(
            tools.automaticAuthorization(automatic, cancel, args("""{"graph_id":"missing"}""")),
        )
        val cancellation = assertIs<AgentToolAutomaticAuthorization.Allowed>(
            tools.automaticAuthorization(automatic, cancel, ids),
        )
        assertFalse(tools.execute(automatic.copy(authorization = cancellation.approval), cancel.name, ids).isError)
        assertTrue(reviewer.changes.isEmpty())
    }
}
