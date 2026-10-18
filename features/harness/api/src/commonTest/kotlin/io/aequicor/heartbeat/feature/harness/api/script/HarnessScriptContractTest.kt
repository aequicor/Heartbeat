package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.event.HarnessEvent
import io.aequicor.heartbeat.feature.harness.api.event.SystemEvent
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class HarnessScriptContractTest {
    @Test
    fun `workflow evaluation registers its definition without running it`() {
        var isExecuted = false
        val body: WorkflowDefinition = {
            isExecuted = true
            JsonNull
        }
        val registrations = mutableListOf<WorkflowDefinition>()
        object : HarnessWorkflowBase(WorkflowRegistration { registrations += it }) {
            init {
                workflow(body)
            }
        }
        assertEquals(1, registrations.size)
        assertSame(body, registrations.single())
        assertFalse(isExecuted)
    }

    @Test
    fun `script base routes typed events and tools through the injected activation`() = runTest {
        val subscribed = mutableListOf<KClass<out HarnessEvent>>()
        val actions = mutableListOf<AgentToolAction>()
        val events = object : ScriptEvents {
            override fun <E : HarnessEvent> on(type: KClass<E>, handler: suspend (E) -> Unit): ScriptRegistration {
                subscribed += type
                return ScriptRegistration { }
            }
        }
        val agent = object : ScriptAgent {
            override fun tool(
                name: ItemName,
                description: String,
                schema: JsonObject,
                action: AgentToolAction,
                handler: suspend (ScriptToolCall) -> ScriptToolResult,
            ): ScriptRegistration {
                actions += action
                return ScriptRegistration { }
            }

            override fun instructions(handler: suspend (AgentToolScope) -> String): ScriptRegistration = error("unused")
        }
        val methods = mutableListOf<ItemName>()
        val ui = object : ScriptUi {
            override fun method(
                name: ItemName,
                description: String,
                handler: suspend (ScriptUiCall) -> ScriptUiResult,
            ): ScriptRegistration {
                methods += name
                return ScriptRegistration { }
            }
        }
        val context = ContractScriptScope(backgroundScope, events, agent, ui)
        val evaluated = object : HarnessScriptBase(context) {
            init {
                this.events.on<SystemEvent.Started> { }
                this.agent.tool(ItemName("inspect"), "Private description", JsonObject(emptyMap())) {
                    ScriptToolResult("Private output")
                }
                this.ui.method(ItemName("prs"), "Private description") { ScriptUiResult.Value(JsonObject(emptyMap())) }
            }
        }
        assertSame(context, evaluated.script)
        assertEquals(listOf<KClass<out HarnessEvent>>(SystemEvent.Started::class), subscribed)
        assertEquals(listOf(AgentToolAction.Command), actions)
        assertEquals(listOf(ItemName("prs")), methods)
        assertEquals(8, HARNESS_API_VERSION)
    }

    @Test
    fun `ui calls and results never print user data`() {
        val secret = "private-value"
        val texts = listOf(
            ScriptUiCall(JsonObject(mapOf("token" to JsonPrimitive(secret)))).toString(),
            ScriptUiResult.Value(JsonPrimitive(secret)).toString(),
            ScriptUiResult.Error(secret).toString(),
            ScriptCommand(secret, listOf(secret)).toString(),
            ScriptCommandResult.Exited(1, secret).toString(),
            ScriptCommandResult.TimedOut(secret).toString(),
        )
        for (text in texts) assertFalse(secret in text, text)
    }
}

private class ContractScriptScope(
    override val scope: CoroutineScope,
    override val events: ScriptEvents,
    override val agent: ScriptAgent,
    override val ui: ScriptUi,
) : HarnessScriptScope {
    override val harness = HarnessId("harness")
    override val name = HarnessName("sample")
    override val item = ItemId("script")
    override val revision = 1L
    override val data: ScriptData get() = error("unused")
    override val processes: ScriptProcesses get() = error("unused")
    override val hooks: ScriptHooks get() = error("unused")
    override val sessions: ScriptSessions get() = error("unused")
    override val scheduler: ScriptScheduler get() = error("unused")
    override val prompts: ScriptPrompts get() = error("unused")
    override val workflows: ScriptWorkflows get() = error("unused")
    override val commands: ScriptCommands get() = error("unused")
}
