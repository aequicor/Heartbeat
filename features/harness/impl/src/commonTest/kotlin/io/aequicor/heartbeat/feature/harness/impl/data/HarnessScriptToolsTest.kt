package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolResult
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.HarnessScriptTools
import io.aequicor.heartbeat.feature.harness.impl.data.services.SchedulerAccessToggles
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessDispatchFixture
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessSessionProofs
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessToolDispatch
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.MemoryHarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HarnessScriptToolsTest {
    private val args = JsonObject(emptyMap())
    private val context = AgentToolContext(dispatchSession, null, TurnId("turn"), request = RequestId("request"))

    @Test
    fun `gate snapshot pins handler and execution preserves trusted request ancestry`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var calls = 0
        var observed: HarnessCallOrigin? = null
        fixture.onEvaluate = { script ->
            script.agent.tool(ItemName("check"), "Check", args) {
                calls++
                observed = currentCoroutineContext()[HarnessOriginContext]?.origin
                ScriptToolResult("done")
            }
        }
        fixture.activate()
        val ancestry = MemoryHarnessRequestAncestry()
        val origin = HarnessCallOrigin(sendChain = mapOf(fixture.request.harness.id to 2))
        ancestry.restrict(context.session, context.request!!, origin)
        val tools = tools(fixture, SchedulerAccessToggles(), ancestry)
        val spec = tools.specifications(AgentToolScope(null, session = dispatchSession)).single()
        assertTrue(tools.execute(context, spec.name, args).isError)
        val approved = tools.approval(context, spec, args)
        assertFalse(tools.execute(context.copy(authorization = approved), spec.name, args).isError)
        assertEquals(origin, observed)
        fixture.desired = fixture.desired.copy(generation = 2, harness = fixture.desired.harness.copy(revision = 2))
        fixture.activate()
        assertTrue(tools.execute(context.copy(authorization = approved), spec.name, args).isError)
        assertEquals(1, calls)
    }

    @Test
    fun `catalog retains frozen names and turn cleanup revokes tools even after toggle off`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.onEvaluate = { script ->
            script.agent.tool(ItemName("check"), "Check", args) { ScriptToolResult("done") }
        }
        fixture.activate()
        val toggles = SchedulerAccessToggles()
        val tools = tools(fixture, toggles, MemoryHarnessRequestAncestry())
        val spec = tools.specifications(AgentToolScope(null, session = dispatchSession)).single()
        val approval = tools.approval(context, spec, args)
        assertNotNull(approval.binding)
        toggles.enabled.value = false
        tools.finishTurn(context.session, context.turn)
        assertEquals(listOf(spec.name), tools.catalog.map { it.name })
        assertTrue(tools.specifications(null).isEmpty())
        toggles.enabled.value = true
        assertTrue(tools.execute(context.copy(authorization = approval), spec.name, args).isError)
    }
}

private fun tools(
    fixture: HarnessDispatchFixture,
    toggles: SchedulerAccessToggles,
    ancestry: MemoryHarnessRequestAncestry,
): HarnessScriptTools = HarnessScriptTools(
    toggles,
    lazy { HarnessActiveAccess { _, _ -> listOf(fixture.request.harness) } },
    lazy { fixture.runtime },
    lazy { HarnessToolDispatch(fixture.runtime, fixture.sessions) },
    lazy {
        HarnessSessionProofs(
            { HarnessState.Ready(listOf(HarnessEntry(fixture.request.harness)), isRuntimeAvailable = true) },
            { null },
            { null },
        )
    },
    lazy { HarnessRequestOrigins(ancestry) },
)
