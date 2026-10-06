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
        val context = ContractScriptScope(backgroundScope, events, agent)
        val evaluated = object : HarnessScriptBase(context) {
            init {
                this.events.on<SystemEvent.Started> { }
                this.agent.tool(ItemName("inspect"), "Private description", JsonObject(emptyMap())) {
                    ScriptToolResult("Private output")
                }
            }
        }
        assertSame(context, evaluated.script)
        assertEquals(listOf<KClass<out HarnessEvent>>(SystemEvent.Started::class), subscribed)
        assertEquals(listOf(AgentToolAction.Command), actions)
        assertEquals(1, HARNESS_API_VERSION)
    }
}

private class ContractScriptScope(
    override val scope: CoroutineScope,
    override val events: ScriptEvents,
    override val agent: ScriptAgent,
) : HarnessScriptScope {
    override val harness = HarnessId("harness")
    override val name = HarnessName("sample")
    override val item = ItemId("script")
    override val revision = 1L
    override val hooks: ScriptHooks get() = error("unused")
    override val sessions: ScriptSessions get() = error("unused")
    override val scheduler: ScriptScheduler get() = error("unused")
    override val prompts: ScriptPrompts get() = error("unused")
    override val workflows: ScriptWorkflows get() = error("unused")
}
