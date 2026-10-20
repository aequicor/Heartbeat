package io.aequicor.heartbeat.feature.agentlearning.impl.data

import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.agentlearning.api.LearningTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LearningOperationBindingTest {
    @Test
    fun `exact facade authorization saves without a review model`() = runTest {
        val machine = SpecMachine()
        val tools = tools(machine)
        val context = context(target = null)
        val args = arguments()
        val spec = tools.specifications(PROJECT).first()
        val approval = tools.approval(context, spec, args)
        assertFalse(tools.execute(context.copy(authorization = approval), LearningTools.REMEMBER, args).isError)
        assertEquals(1, (machine.state.value as AgentLearningState.Ready).instructions.size)
    }

    @Test
    fun `changed content or caller cannot reuse a facade authorization`() = runTest {
        val machine = SpecMachine()
        val tools = tools(machine)
        val context = context()
        val args = arguments()
        val approval = tools.approval(context, tools.specifications(PROJECT).first(), args)
        val approved = context.copy(authorization = approval)
        val callers = listOf(
            approved.copy(session = approved.session.copy(nativeId = "other")),
            approved.copy(workspace = null),
            approved.copy(turn = TurnId("other")),
            approved.copy(callId = ToolCallId("other")),
        )
        for (caller in callers) assertTrue(tools.execute(caller, LearningTools.REMEMBER, args).isError)
        assertTrue(tools.execute(approved, LearningTools.REMEMBER, arguments("content" to "Changed")).isError)
        assertTrue(tools.execute(context, LearningTools.REMEMBER, args).isError)
        assertTrue((machine.state.value as AgentLearningState.Ready).instructions.isEmpty())
    }

    @Test
    fun `remapping a checkout invalidates authorization and a fresh binding names the new project`() = runTest {
        val task = WorktreeTask("chat", PROJECT, executionWorkspace = CHECKOUT)
        val worktrees = Worktrees(mapOf("chat" to task))
        val machine = SpecMachine()
        val tools = tools(machine, worktrees = worktrees)
        val context = context(CHECKOUT)
        val spec = tools.specifications(CHECKOUT).first()
        val args = arguments()
        val approval = tools.approval(context, spec, args)
        val replacement = WorkspaceRef("other-project")
        worktrees.updateTasks(mapOf("chat" to task.copy(project = replacement)))
        assertTrue(tools.execute(context.copy(authorization = approval), LearningTools.REMEMBER, args).isError)
        val fresh = tools.approval(context, spec, args)
        assertNotEquals(approval, fresh)
        assertFalse(tools.execute(context.copy(authorization = fresh), LearningTools.REMEMBER, args).isError)
        assertEquals(replacement, (machine.state.value as AgentLearningState.Ready).instructions.single().project)
    }
}

private fun arguments(vararg extra: Pair<String, String>): JsonObject {
    val properties = mapOf(
        "kind" to "general",
        "title" to "Preference",
        "content" to "Use concise responses",
        "safety" to "safe",
    ) + extra
    return JsonObject(properties.mapValues { JsonPrimitive(it.value) })
}
