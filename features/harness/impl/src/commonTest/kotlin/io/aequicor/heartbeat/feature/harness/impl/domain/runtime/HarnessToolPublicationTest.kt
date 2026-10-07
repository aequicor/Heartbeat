package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptScope
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessToolPublicationTest {
    @Test
    fun `whole harness quota includes other items but excludes replaced item`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.evaluate = { it.tools(5) }
        val first = fixture.activate()
        fixture.desired = scriptRequest(2, item = "second")
        fixture.evaluate = { it.tools(4, offset = 5) }
        assertFalse(fixture.runtime.activate(fixture.desired))
        assertSame(first, fixture.runtime.published().single())
        fixture.desired = scriptRequest(3, item = "second")
        fixture.evaluate = { it.tools(3, offset = 5) }
        fixture.activate()
        fixture.desired = scriptRequest(4)
        fixture.evaluate = { it.tools(5) }
        fixture.activate()
        assertEquals(2, fixture.runtime.published().size)
    }

    @Test
    fun `duplicate names and changed published shape preserve old code`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.evaluate = { it.tools(1) }
        val first = fixture.activate()
        fixture.desired = scriptRequest(2, item = "second")
        assertFalse(fixture.runtime.activate(fixture.desired))
        fixture.desired = scriptRequest(3)
        fixture.evaluate = { it.tools(1, shape = "integer") }
        assertFalse(fixture.runtime.activate(fixture.desired))
        fixture.desired = scriptRequest(4)
        fixture.evaluate = { it.tools(1, action = AgentToolAction.Read) }
        assertFalse(fixture.runtime.activate(fixture.desired))
        assertSame(first, fixture.runtime.published().single())
        fixture.desired = scriptRequest(5)
        fixture.evaluate = { it.tools(1) }
        assertTrue(fixture.runtime.activate(fixture.desired))
    }

    @Test
    fun `unsuccessful evaluation does not reserve declaration names`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.evaluate = { it.tools(1) }
        fixture.isEvaluationSuccessful = false
        assertFalse(fixture.runtime.activate(fixture.desired))
        fixture.desired = scriptRequest(2)
        fixture.isEvaluationSuccessful = true
        fixture.evaluate = { it.tools(1, shape = "integer") }
        assertTrue(fixture.runtime.activate(fixture.desired))
    }

    @Test
    fun `deleted namespace can only reuse its published shape`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.evaluate = { it.tools(1) }
        val first = fixture.activate()
        fixture.runtime.deactivate(HarnessEffect.Deactivate(listOf(first.request), false, generation = 1))
        runCurrent()
        val second = scriptRequest(2)
        fixture.desired = second.copy(harness = second.harness.copy(id = HarnessId("newowner")))
        fixture.evaluate = { it.tools(1, shape = "integer") }
        assertFalse(fixture.runtime.activate(fixture.desired))
        fixture.evaluate = { it.tools(1) }
        assertTrue(fixture.runtime.activate(fixture.desired))
    }
}

private fun HarnessScriptScope.tools(
    count: Int,
    offset: Int = 0,
    shape: String = "object",
    action: AgentToolAction = AgentToolAction.Command,
) {
    repeat(count) {
        agent.tool(ItemName("tool${it + offset}"), "Tool", JsonObject(mapOf("type" to JsonPrimitive(shape))), action) {
            ScriptToolResult("result")
        }
    }
}
