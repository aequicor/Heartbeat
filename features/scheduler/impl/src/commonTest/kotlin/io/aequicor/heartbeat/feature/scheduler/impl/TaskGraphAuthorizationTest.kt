package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphState
import io.aequicor.heartbeat.feature.scheduler.impl.data.TaskGraphToolSpecs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskGraphAuthorizationTest {
    private val context = AgentToolContext(
        SESSION,
        PROJECT,
        TurnId("turn"),
        RequestId("request"),
        callId = ToolCallId("call"),
        target = TARGET,
        decisionMode = AgentToolDecisionMode.AutomaticSafety,
        permissions = AgentToolPermissions { error("Only the facade requests permissions") },
    )
    private val spec = TaskGraphToolSpecs.all.first { it.name == TaskGraphToolSpecs.CREATE }
    private val create = args(
        """{"graph_id":"graph","tasks":[
          {"id":"A","action":{"kind":"command","command":"./gradlew jvmTest","timeoutSeconds":300}},
          {"id":"B","action":{"kind":"agent","prompt":"Inspect test results","title":"Review"},
           "dependencies":{"kind":"all","tasks":[{"task":"A","outcome":"finished"}]}}]}""",
    )

    @Test
    fun `the complete graph can execute without a model review and retries preserve its identity`() = runTest {
        val f = GraphFixture(this)
        val tools = f.tools()
        val caller = context
        val approval = tools.approval(caller, spec, create)
        assertTrue(tools.execute(caller, spec.name, create).isError)
        assertTrue(approval.description.orEmpty().contains("./gradlew jvmTest"))
        repeat(2) {
            assertFalse(tools.execute(caller.copy(authorization = approval), spec.name, create).isError)
        }
        assertEquals(1, (f.machine.state.value as TaskGraphState.Ready).graphs.size)
        assertEquals(approval.binding, f.machine.graph().approval)
    }

    @Test
    fun `authorization cannot change graph content workspace or owner`() = runTest {
        val f = GraphFixture(this)
        val tools = f.tools()
        val approval = tools.approval(context, spec, create)
        val authorized = context.copy(authorization = approval)
        val changed = args(create.toString().replace("./gradlew jvmTest", "delete files"))
        assertTrue(tools.execute(authorized, spec.name, changed).isError)
        assertTrue(tools.execute(authorized.copy(session = OTHER), spec.name, create).isError)
        assertTrue(tools.execute(authorized.copy(workspace = null), spec.name, create).isError)
        assertTrue((f.machine.state.value as TaskGraphState.Ready).graphs.isEmpty())
        assertFalse(tools.execute(authorized, spec.name, create).isError)
    }

    private fun args(text: String) = Json.parseToJsonElement(text) as JsonObject
}
