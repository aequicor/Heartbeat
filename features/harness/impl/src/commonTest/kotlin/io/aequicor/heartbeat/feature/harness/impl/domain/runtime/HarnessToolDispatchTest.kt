package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolCall
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HarnessToolDispatchTest {
    @Test
    fun `binding from previous generation cannot execute replacement handler`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var calls = 0
        fixture.onEvaluate = { script ->
            script.agent.tool(ItemName("check"), "Check", JsonObject(emptyMap())) {
                calls++
                ScriptToolResult("done")
            }
        }
        fixture.activate()
        val tools = HarnessToolDispatch(fixture.runtime, fixture.sessions)
        val old = tools.bindings(dispatchSession).single()
        fixture.desired = fixture.desired.copy(
            harness = fixture.desired.harness.copy(revision = 2),
            generation = 2,
        )
        fixture.activate()
        val replacement = tools.bindings(dispatchSession).single()
        assertNotEquals(old.key, replacement.key)
        assertTrue(tools.execute(old, toolCall()).isError)
        assertEquals(0, calls)
        assertEquals("done", tools.execute(replacement, toolCall()).text)
        assertEquals(1, calls)
        fixture.isSessionAllowed = false
        assertTrue(tools.execute(replacement, toolCall()).isError)
        assertEquals(1, calls)
    }

    @Test
    fun `cancelled lifetime and finished turn reject before author code`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var calls = 0
        fixture.onEvaluate = { script ->
            script.agent.tool(ItemName("check"), "Check", JsonObject(emptyMap())) {
                calls++
                ScriptToolResult("done")
            }
        }
        fixture.activate()
        val tools = HarnessToolDispatch(fixture.runtime, fixture.sessions)
        val binding = tools.bindings(dispatchSession).single()
        val lifetime = Job().apply { cancel() }
        assertTrue(tools.execute(binding, toolCall(lifetime)).isError)
        assertTrue(tools.finishTurn(dispatchSession, TurnId("turn")))
        assertTrue(tools.execute(binding, toolCall()).isError)
        assertEquals(0, calls)
    }

    @Test
    fun `timeout retains actual tool ownership until uncooperative cleanup finishes`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        fixture.onEvaluate = { script ->
            script.agent.tool(ItemName("check"), "Check", JsonObject(emptyMap())) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                ScriptToolResult("late")
            }
        }
        val instance = fixture.activate()
        val tools = HarnessToolDispatch(fixture.runtime, fixture.sessions)
        val binding = tools.bindings(dispatchSession).single()
        val result = async { tools.execute(binding, toolCall()) }
        entered.await()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(result.await().isError)
        assertEquals(1, instance.calls.activeCount)
        val cleanup = async { tools.finishTurn(dispatchSession, TurnId("turn")) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertFalse(cleanup.await())
        assertEquals(1, instance.calls.activeCount)
        assertFalse(fixture.runtime.deactivate(fixture.deactivation()))
        release.complete(Unit)
        runCurrent()
        assertEquals(0, instance.calls.activeCount)
        assertTrue(tools.finishTurn(dispatchSession, TurnId("turn")))
        assertTrue(fixture.runtime.deactivate(fixture.deactivation()))
    }

    @Test
    fun `instructions preserve exact declared scope and enforce aggregate character limit`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val observed = mutableListOf<AgentToolScope>()
        fixture.onEvaluate = { script ->
            script.agent.instructions { scope ->
                observed += scope
                "a".repeat(HarnessLimits.INSTRUCTION_CHARS)
            }
            script.agent.instructions { scope ->
                observed += scope
                "truncated"
            }
        }
        fixture.activate()
        val tools = HarnessToolDispatch(fixture.runtime, fixture.sessions)
        val scope = AgentToolScope(null, declared = setOf("allowed"), session = dispatchSession)
        assertEquals("a".repeat(HarnessLimits.INSTRUCTION_CHARS), tools.instructions(scope))
        assertEquals(listOf(scope, scope), observed)
        assertEquals("", tools.instructions(scope.copy(session = null)))
        fixture.isSessionAllowed = false
        assertEquals("", tools.instructions(scope))
        assertEquals(2, observed.size)
    }

    @Test
    fun `slow instruction callback fails open within half a second`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.onEvaluate = { script ->
            script.agent.instructions { awaitCancellation() }
            script.agent.instructions { "available" }
        }
        fixture.activate()
        val tools = HarnessToolDispatch(fixture.runtime, fixture.sessions)
        val result = async { tools.instructions(AgentToolScope(null, session = dispatchSession)) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals("available", result.await())
        assertEquals(500L, testScheduler.currentTime)
    }
}

private fun toolCall(lifetime: Job? = null) = ScriptToolCall(
    AgentToolContext(dispatchSession, null, TurnId("turn"), lifetime = lifetime),
    JsonObject(emptyMap()),
)
