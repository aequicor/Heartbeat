package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskRun
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
        val approval = assertNotNull(tools.existingAuthorization(context, spec, decision))
        assertNull(tools.existingAuthorization(context.copy(session = OTHER), spec, decision))
        assertFalse(tools.execute(context.copy(authorization = approval), spec.name, decision).isError)
        assertNull(tools.existingAuthorization(context, spec, decision))
        assertTrue(tools.execute(context.copy(authorization = approval), spec.name, decision).isError)
    }
}
