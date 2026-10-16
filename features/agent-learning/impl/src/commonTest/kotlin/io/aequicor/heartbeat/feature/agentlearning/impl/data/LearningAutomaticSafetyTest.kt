package io.aequicor.heartbeat.feature.agentlearning.impl.data

import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.agentlearning.api.LearningApproval
import io.aequicor.heartbeat.feature.agentlearning.api.LearningTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningAutomaticSafetyTest {
    @Test
    fun `remember declares mutation and every local approval level receives independent review`() = runTest {
        for (level in LearningApproval.entries) {
            val machine = SpecMachine(AgentLearningState.Ready(approval = level))
            var reviews = 0
            val tools = tools(
                machine,
                reviewer = AgentSafetyReviewer { target, policy, change ->
                    reviews++
                    assertEquals(TARGET, target)
                    assertTrue("untrusted data" in policy)
                    val snapshot = Json.parseToJsonElement(change).jsonObject
                    assertEquals("Use concise responses", snapshot["content"]?.jsonPrimitive?.content)
                    AgentSafetyReview("safe", "User preference")
                },
            )
            val context = automaticContext()
            val spec = tools.specifications(PROJECT).first { it.name == LearningTools.REMEMBER }
            assertEquals(AgentToolAction.Edit, spec.action)
            val approval = tools.approval(context, spec, arguments())
            val decision = assertIs<AgentToolAutomaticAuthorization.Allowed>(
                tools.automaticAuthorization(context, spec, arguments()),
            )
            assertEquals(approval, decision.approval)
            val result = tools.execute(context.copy(authorization = approval), LearningTools.REMEMBER, arguments())
            assertFalse(result.isError, result.text)
            assertEquals(1, reviews)
            assertEquals(1, (machine.state.value as AgentLearningState.Ready).instructions.size)
        }
    }

    @Test
    fun `an agent rating does not replace independent review and review requests can still be safe`() = runTest {
        for (rating in listOf("safe", "review")) {
            val tools = tools(
                reviewer = AgentSafetyReviewer { _, _, change ->
                    val snapshot = Json.parseToJsonElement(change).jsonObject
                    assertEquals((rating == "safe").toString(), snapshot["agentRatedSafe"].toString())
                    AgentSafetyReview("safe", "Bounded preference")
                },
            )
            val spec = tools.specifications(PROJECT).first()
            assertIs<AgentToolAutomaticAuthorization.Allowed>(
                tools.automaticAuthorization(automaticContext(), spec, arguments("safety" to rating)),
            )
        }
    }

    @Test
    fun `risk and uncertainty refuse even when the instruction rates itself safe`() = runTest {
        for (verdict in listOf("risk", "uncertain")) {
            val machine = SpecMachine(AgentLearningState.Ready(approval = LearningApproval.AcceptAll))
            val tools = tools(
                machine,
                reviewer = AgentSafetyReviewer { _, _, _ -> AgentSafetyReview(verdict, "Unsafe") },
            )
            val spec = tools.specifications(PROJECT).first()
            val context = automaticContext()
            assertIs<AgentToolAutomaticAuthorization.Denied>(tools.automaticAuthorization(context, spec, arguments()))
            val forged = context.copy(authorization = tools.approval(context, spec, arguments()))
            assertTrue(tools.execute(forged, LearningTools.REMEMBER, arguments()).isError)
            assertTrue((machine.state.value as AgentLearningState.Ready).instructions.isEmpty())
        }
    }

    @Test
    fun `missing model and unavailable reviewer fail closed while cancellation propagates`() = runTest {
        var reviews = 0
        val tools = tools(
            reviewer = AgentSafetyReviewer { _, _, _ ->
                reviews++
                error("private failure")
            },
        )
        val spec = tools.specifications(PROJECT).first()
        assertIs<AgentToolAutomaticAuthorization.Denied>(
            tools.automaticAuthorization(automaticContext().copy(target = null), spec, arguments()),
        )
        assertEquals(0, reviews)
        val refused = assertIs<AgentToolAutomaticAuthorization.Denied>(
            tools.automaticAuthorization(automaticContext(), spec, arguments()),
        )
        assertFalse("private failure" in refused.reason)
        assertEquals(1, reviews)
        val cancelled = tools(reviewer = AgentSafetyReviewer { _, _, _ -> throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> {
            cancelled.automaticAuthorization(automaticContext(), spec, arguments())
        }
    }

    @Test
    fun `full model and skill content and resolved project are reviewed`() = runTest {
        val calls = mutableListOf<JsonObject>()
        val tools = tools(
            worktrees = Worktrees(mapOf("chat" to WorktreeTask("chat", PROJECT, executionWorkspace = CHECKOUT))),
            reviewer = AgentSafetyReviewer { _, _, change ->
                calls += Json.parseToJsonElement(change).jsonObject
                AgentSafetyReview("safe", "Local instruction")
            },
        )
        val spec = tools.specifications(PROJECT).first()
        val model = arguments("kind" to "model", "model_scope" to "model")
        tools.automaticAuthorization(automaticContext().copy(workspace = CHECKOUT), spec, model)
        assertEquals("project", calls.last()["project"]?.jsonObject?.get("value")?.jsonPrimitive?.content)
        val modelScope = calls.last()["modelScope"]?.jsonObject
        assertEquals("sonnet", modelScope?.get("model")?.jsonObject?.get("value")?.jsonPrimitive?.content)
        val skill = arguments("kind" to "skill", "description" to "When writing", "content" to "x".repeat(16_000))
        tools.automaticAuthorization(automaticContext().copy(workspace = null), spec, skill)
        assertEquals(16_000, calls.last()["content"]?.jsonPrimitive?.content?.length)
        assertEquals("null", calls.last()["project"].toString())
    }

    @Test
    fun `changed content or caller cannot reuse a safe authorization`() = runTest {
        var reviews = 0
        val machine = SpecMachine()
        val tools = tools(
            machine,
            reviewer = AgentSafetyReviewer { _, _, _ ->
                reviews++
                AgentSafetyReview("safe", "Bounded instruction")
            },
        )
        val context = automaticContext()
        val spec = tools.specifications(PROJECT).first()
        val decision = assertIs<AgentToolAutomaticAuthorization.Allowed>(
            tools.automaticAuthorization(context, spec, arguments()),
        )
        val approved = context.copy(authorization = decision.approval)
        val changed = listOf(
            approved.copy(session = approved.session.copy(nativeId = "another")),
            approved.copy(workspace = null),
            approved.copy(turn = TurnId("another")),
            approved.copy(request = RequestId("another")),
            approved.copy(callId = ToolCallId("another")),
            approved.copy(target = TARGET.copy(model = ModelId("another"))),
        )
        for (caller in changed) {
            assertTrue(tools.execute(caller, LearningTools.REMEMBER, arguments()).isError)
        }
        assertTrue(tools.execute(approved, LearningTools.REMEMBER, arguments("content" to "Changed")).isError)
        assertTrue(tools.execute(context, LearningTools.REMEMBER, arguments()).isError)
        assertTrue((machine.state.value as AgentLearningState.Ready).instructions.isEmpty())
        assertEquals(1, reviews)
    }

    @Test
    fun `changed learning policy invalidates the reviewed snapshot`() = runTest {
        val machine = SpecMachine()
        val tools = tools(machine)
        val context = automaticContext()
        val spec = tools.specifications(PROJECT).first()
        val decision = assertIs<AgentToolAutomaticAuthorization.Allowed>(
            tools.automaticAuthorization(context, spec, arguments()),
        )
        machine.state.value = AgentLearningState.Ready(approval = LearningApproval.AcceptAll)
        assertTrue(
            tools.execute(context.copy(authorization = decision.approval), LearningTools.REMEMBER, arguments()).isError,
        )
        assertTrue((machine.state.value as AgentLearningState.Ready).instructions.isEmpty())
    }

    @Test
    fun `invalid instructions and disabled learning do not reach the reviewer`() = runTest {
        val toggles = Toggles()
        val tools = tools(toggles = toggles, reviewer = AgentSafetyReviewer { _, _, _ -> error("must not review") })
        val spec = tools.specifications(PROJECT).first()
        val invalid = listOf(arguments("content" to "api_key=abcdef1234567890"), JsonObject(emptyMap()))
        for (arguments in invalid) {
            assertIs<AgentToolAutomaticAuthorization.Denied>(
                tools.automaticAuthorization(automaticContext(), spec, arguments),
            )
        }
        toggles.isEnabled = false
        assertIs<AgentToolAutomaticAuthorization.Denied>(
            tools.automaticAuthorization(automaticContext(), spec, arguments()),
        )
    }

    @Test
    fun `ordinary interactive learning and read-only skill loads retain their existing gate`() = runTest {
        val tools = tools(reviewer = AgentSafetyReviewer { _, _, _ -> error("must not review") })
        val specs = tools.specifications(PROJECT)
        assertNull(tools.automaticAuthorization(context(), specs.first(), arguments()))
        assertNull(tools.automaticAuthorization(automaticContext(), specs.last(), arguments()))
    }

    @Test
    fun `the same reviewed call reuses its exact verdict`() = runTest {
        var reviews = 0
        val tools = tools(
            reviewer = AgentSafetyReviewer { _, _, _ ->
                reviews++
                AgentSafetyReview("safe", "Bounded instruction")
            },
        )
        val spec = tools.specifications(PROJECT).first()
        repeat(2) {
            assertIs<AgentToolAutomaticAuthorization.Allowed>(
                tools.automaticAuthorization(automaticContext(), spec, arguments()),
            )
        }
        assertEquals(1, reviews)
    }
}

private fun automaticContext(): AgentToolContext = context().copy(decisionMode = AgentToolDecisionMode.AutomaticSafety)

private fun arguments(vararg extra: Pair<String, String>): JsonObject {
    val properties = mapOf(
        "kind" to "general",
        "title" to "Preference",
        "content" to "Use concise responses",
        "safety" to "safe",
    ) + extra
    return JsonObject(properties.mapValues { JsonPrimitive(it.value) })
}
