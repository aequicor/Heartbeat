package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskRun
import io.aequicor.heartbeat.feature.scheduler.api.TaskGraphState
import io.aequicor.heartbeat.feature.scheduler.impl.data.TaskGraphToolSpecs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskGraphSafetyTest {
    private val context = AgentToolContext(
        SESSION,
        PROJECT,
        TurnId("turn"),
        RequestId("request"),
        callId = ToolCallId("call"),
        target = TARGET,
        decisionMode = AgentToolDecisionMode.AutomaticSafety,
        permissions = AgentToolPermissions { error("Automatic mode must never ask the user") },
    )
    private val spec = TaskGraphToolSpecs.all.first { it.name == TaskGraphToolSpecs.CREATE }
    private val create = args(
        """{"graph_id":"graph","tasks":[
          {"id":"A","action":{"kind":"command","command":"./gradlew jvmTest","timeoutSeconds":300}},
          {"id":"B","action":{"kind":"agent","prompt":"Inspect test results","title":"Review"},
           "dependencies":{"kind":"all","tasks":[{"task":"A","outcome":"finished"}]}}]}""",
    )

    @Test
    fun `automatic creation reviews every node and executes only once without prompting`() = runTest {
        val f = GraphFixture(this)
        var reviews = 0
        val parentModel = TARGET.copy(model = ModelId("parent-model"))
        val tools = f.tools { target, policy, change ->
            reviews++
            assertEquals(parentModel, target)
            assertTrue(policy.contains("without a\nsecond review"))
            assertTrue(change.contains("./gradlew jvmTest"))
            assertTrue(change.contains("Inspect test results"))
            assertTrue(change.contains("finished"))
            assertTrue(change.contains("300"))
            AgentSafetyReview("safe", "Bounded project work")
        }
        val caller = context.copy(reviewTarget = parentModel)
        val approval = tools.approval(caller, spec, create)
        assertTrue(tools.execute(caller.copy(authorization = approval), spec.name, create).isError)
        repeat(2) {
            assertEquals(
                AgentToolAutomaticAuthorization.Allowed(approval),
                tools.automaticAuthorization(caller, spec, create),
            )
        }
        assertEquals(1, reviews)
        val authorized = caller.copy(authorization = approval)
        assertFalse(tools.execute(authorized, spec.name, create).isError)
        assertTrue(tools.execute(authorized, spec.name, create).isError)
        assertEquals(approval.binding, f.machine.graph().approval)
    }

    @Test
    fun `a reviewed graph cannot authorize a different caller request call model or graph`() = runTest {
        val f = GraphFixture(this)
        val tools = f.tools { _, _, _ -> AgentSafetyReview("safe", "Safe") }
        val approval = tools.approval(context, spec, create)
        assertIs<AgentToolAutomaticAuthorization.Allowed>(tools.automaticAuthorization(context, spec, create))
        val authorized = context.copy(authorization = approval)
        listOf(
            authorized.copy(session = OTHER),
            authorized.copy(workspace = null),
            authorized.copy(turn = TurnId("other-turn")),
            authorized.copy(request = RequestId("other-request")),
            authorized.copy(callId = ToolCallId("other-call")),
            authorized.copy(target = TARGET.copy(model = ModelId("other-model"))),
            authorized.copy(reviewTarget = TARGET.copy(model = ModelId("parent-model"))),
            authorized.copy(trust = TrustLevel.Full),
        ).forEach { assertTrue(tools.execute(it, spec.name, create).isError) }
        val changed = args(create.toString().replace("./gradlew jvmTest", "delete files"))
        assertTrue(tools.execute(authorized, spec.name, changed).isError)
        assertTrue((f.machine.state.value as TaskGraphState.Ready).graphs.isEmpty())
        assertFalse(tools.execute(authorized, spec.name, create).isError)
    }

    @Test
    fun `risk uncertainty malformed verdicts and unavailable reviews cannot start a graph`() = runTest {
        for (verdict in listOf("risky", "uncertain", "SAFE", "safe")) {
            val f = GraphFixture(this)
            val tools = f.tools { _, _, _ ->
                AgentSafetyReview(verdict, if (verdict == "safe") "" else "Not safe")
            }
            val approval = tools.approval(context, spec, create)
            assertIs<AgentToolAutomaticAuthorization.Denied>(tools.automaticAuthorization(context, spec, create))
            assertTrue(tools.execute(context.copy(authorization = approval), spec.name, create).isError)
            assertTrue((f.machine.state.value as TaskGraphState.Ready).graphs.isEmpty())
        }
        val f = GraphFixture(this)
        val tools = f.tools { _, _, _ -> error("Unavailable") }
        assertIs<AgentToolAutomaticAuthorization.Denied>(tools.automaticAuthorization(context, spec, create))
        assertIs<AgentToolAutomaticAuthorization.Denied>(
            tools.automaticAuthorization(context.copy(target = null), spec, create),
        )
    }

    @Test
    fun `cancelling a review propagates and leaves no graph authorization`() = runTest {
        val f = GraphFixture(this)
        val tools = f.tools { _, _, _ -> throw CancellationException("Cancelled") }
        assertFailsWith<CancellationException> { tools.automaticAuthorization(context, spec, create) }
        val approval = tools.approval(context, spec, create)
        assertTrue(tools.execute(context.copy(authorization = approval), spec.name, create).isError)
    }

    @Test
    fun `later creation retry is reviewed again but keeps the durable graph identity`() = runTest {
        val f = GraphFixture(this)
        var reviews = 0
        val tools = f.tools { _, _, _ ->
            reviews++
            AgentSafetyReview("safe", "Safe")
        }
        for (caller in listOf(context, context.copy(request = RequestId("retry"), callId = ToolCallId("retry")))) {
            val approval = tools.approval(caller, spec, create)
            assertIs<AgentToolAutomaticAuthorization.Allowed>(tools.automaticAuthorization(caller, spec, create))
            assertFalse(tools.execute(caller.copy(authorization = approval), spec.name, create).isError)
        }
        assertEquals(2, reviews)
        assertEquals(1, (f.machine.state.value as TaskGraphState.Ready).graphs.size)
        assertEquals(context.request, f.machine.graph().initiator?.request)
    }

    @Test
    fun `interactive modes preserve trust policy and do not call the reviewer`() = runTest {
        val tools = GraphFixture(this).tools()
        val interactive = context.copy(decisionMode = AgentToolDecisionMode.Interactive)
        assertNull(tools.automaticAuthorization(interactive, spec, create))
        assertTrue(tools.requiresDecision(interactive.copy(trust = TrustLevel.Ask), spec, create))
        assertTrue(tools.requiresDecision(interactive.copy(trust = TrustLevel.AutoEdits), spec, create))
        assertFalse(tools.requiresDecision(interactive.copy(trust = TrustLevel.Full), spec, create))
        val read = TaskGraphToolSpecs.all.first { it.name == TaskGraphToolSpecs.GET }
        assertNull(tools.automaticAuthorization(context, read, args("""{"graph_id":"graph"}""")))
    }

    @Test
    fun `automatic recovery and cancellation review the original graph and exact operation`() = runTest {
        for (name in listOf(TaskGraphToolSpecs.CANCEL, TaskGraphToolSpecs.RESOLVE)) {
            val f = GraphFixture(this)
            var reviewed = ""
            val tools = f.tools { _, _, change ->
                reviewed = change
                AgentSafetyReview("safe", "Safe")
            }
            val interactive = context.copy(decisionMode = AgentToolDecisionMode.Interactive)
            val original = tools.approval(interactive, spec, create)
            assertFalse(tools.execute(interactive.copy(authorization = original), spec.name, create).isError)
            val graph = f.machine.graph().copy(
                runs = mapOf(
                    "A" to GraphTaskRun(phase = GraphTaskPhase.RecoveryRequired, execution = "old", attempt = 1),
                    "B" to GraphTaskRun(),
                ),
            )
            f.machine.state.value = TaskGraphState.Ready(listOf(graph), persisted = 0)
            val operation = TaskGraphToolSpecs.all.first { it.name == name }
            val arguments = args(
                """{"graph_id":"graph","task_id":"A","execution":"old","decision":"retry",
                    "explanation":"Inspected effects"}""",
            )
            val approval = tools.approval(context, operation, arguments)
            assertTrue(tools.execute(context.copy(authorization = approval), name, arguments).isError)
            assertIs<AgentToolAutomaticAuthorization.Allowed>(
                tools.automaticAuthorization(context, operation, arguments),
            )
            assertTrue(reviewed.contains("./gradlew jvmTest"))
            assertTrue(reviewed.contains(name))
            assertFalse(tools.execute(context.copy(authorization = approval), name, arguments).isError)
        }
    }

    private fun args(text: String) = Json.parseToJsonElement(text) as JsonObject
}
