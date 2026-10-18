package io.aequicor.heartbeat.feature.planningmode.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Instant

class PlanningReadOnlyHookTest {
    private val state = MutableStateFlow<PlanningState>(ready(PlanMode.Planning))
    private var isEnabled = true
    private val hook = PlanningReadOnlyHook(View(state)) { isEnabled }

    @Test
    fun `reads and planning tools continue while planning`() = runTest {
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(call("Read", AgentToolAction.Read)))
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(call("plan_write", AgentToolAction.Edit)))
        assertEquals(
            ToolHookVerdict.Continue,
            hook.beforeTool(call("mcp__heartbeat__plan_propose", AgentToolAction.Command)),
        )
        assertEquals(
            ToolHookVerdict.Continue,
            hook.beforeTool(call("WebFetch", AgentToolAction.Command, native = true)),
        )
    }

    @Test
    fun `edits are refused while planning`() = runTest {
        assertIs<ToolHookVerdict.Deny>(hook.beforeTool(call("Write", AgentToolAction.Edit, native = true)))
        assertIs<ToolHookVerdict.Deny>(hook.beforeTool(call("edit_file", AgentToolAction.Edit)))
    }

    @Test
    fun `read-only shell commands continue and others need a decision`() = runTest {
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(shell("Bash", "git log --oneline | head")))
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(shell("run_command", "rg Plan features")))
        assertIs<ToolHookVerdict.Ask>(hook.beforeTool(shell("bash", "./gradlew test")))
        assertIs<ToolHookVerdict.Ask>(hook.beforeTool(shell("powershell", "Get-ChildItem")))
    }

    @Test
    fun `other command tools are refused while planning`() = runTest {
        assertIs<ToolHookVerdict.Deny>(hook.beforeTool(call("Task", AgentToolAction.Command, native = true)))
        assertIs<ToolHookVerdict.Deny>(hook.beforeTool(shell("scheduler_start_action", "ls")))
        assertIs<ToolHookVerdict.Deny>(hook.beforeTool(call("Bash", AgentToolAction.Command, native = true)))
    }

    @Test
    fun `implementing detached and unbound sessions are not restricted`() = runTest {
        state.value = ready(PlanMode.Implementing)
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(call("Write", AgentToolAction.Edit, native = true)))
        state.value = ready(PlanMode.Detached)
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(shell("Bash", "rm -rf build")))
        state.value = ready(PlanMode.Planning)
        val other = SessionRef(EngineId("pi"), SessionSourceId("s"), "other")
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(call("Write", AgentToolAction.Edit, session = other)))
    }

    @Test
    fun `mutations need a decision until plans are loaded`() = runTest {
        state.value = PlanningState.Loading
        assertIs<ToolHookVerdict.Ask>(hook.beforeTool(call("Write", AgentToolAction.Edit)))
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(call("Read", AgentToolAction.Read)))
    }

    @Test
    fun `nothing is intercepted while the feature is off`() = runTest {
        isEnabled = false
        assertFalse(hook.isIntercepting)
        assertEquals(ToolHookVerdict.Continue, hook.beforeTool(call("Write", AgentToolAction.Edit)))
    }

    private fun shell(name: String, command: String) =
        call(name, AgentToolAction.Command, JsonObject(mapOf("command" to JsonPrimitive(command))), native = true)

    private fun call(
        name: String,
        action: AgentToolAction,
        arguments: JsonObject = JsonObject(emptyMap()),
        native: Boolean = false,
        session: SessionRef = Session,
    ) = HookedToolCall(
        SessionHookContext(session, null, null, null, SessionOwner("owner")),
        name,
        action,
        arguments,
        native,
    )

    private fun ready(mode: PlanMode) = PlanningState.Ready(
        plans = mapOf(Chat to Plan(Chat, mode, updatedAt = Instant.fromEpochSeconds(0))),
        bindings = mapOf(Session to Chat),
    )

    private class View(override val state: MutableStateFlow<PlanningState>) : PlanningView

    private companion object {
        val Chat = PlanChat("chat-1")
        val Session = SessionRef(EngineId("pi"), SessionSourceId("s"), "one")
    }
}
