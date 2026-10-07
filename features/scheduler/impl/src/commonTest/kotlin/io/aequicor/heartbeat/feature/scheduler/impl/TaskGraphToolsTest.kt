package io.aequicor.heartbeat.feature.scheduler.impl

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
    fun `causal metadata does not change the literal legacy approval binding`() = runTest {
        val f = GraphFixture(this)
        val tools = TaskGraphTools(f.machine, f.driver)
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
        val tools = TaskGraphTools(f.machine, f.driver)
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
        val tools = TaskGraphTools(f.machine, f.driver)
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
        val tools = TaskGraphTools(f.machine, f.driver)
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
}
