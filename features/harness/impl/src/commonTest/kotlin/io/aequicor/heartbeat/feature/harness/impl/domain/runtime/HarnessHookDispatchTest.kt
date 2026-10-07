package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessHookDispatchTest {
    @Test
    fun `parallel tool hooks share one budget and deny survives another timeout`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var started = 0
        fixture.onEvaluate = { script ->
            repeat(3) {
                script.hooks.beforeTool {
                    started++
                    awaitCancellation()
                }
            }
            script.hooks.beforeTool { ToolHookVerdict.Deny("denied") }
        }
        fixture.activate()
        val result = async { fixture.hooks.beforeTool(fixture.call) }
        runCurrent()
        assertEquals(3, started)
        assertFalse(result.isCompleted)
        advanceTimeBy(900)
        runCurrent()
        assertEquals(ToolHookVerdict.Deny("denied"), result.await())
        assertEquals(900L, testScheduler.currentTime)
    }

    @Test
    fun `self cancelling hook cannot discard another known deny`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.onEvaluate = { script ->
            script.hooks.beforeTool { ToolHookVerdict.Deny("denied") }
            script.hooks.beforeTool { throw CancellationException("callback cancelled itself") }
        }
        fixture.activate()
        assertEquals(ToolHookVerdict.Deny("denied"), fixture.hooks.beforeTool(fixture.call))
        runCurrent()
        assertEquals(1, fixture.feedback.size)
        assertFalse(fixture.feedback.single().isDisabled)
    }

    @Test
    fun `prompt failures and timeout keep successful additions in registration order`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.onEvaluate = { script ->
            script.hooks.beforePrompt { _, _ -> "first" }
            script.hooks.beforePrompt { _, _ -> error("private failure") }
            script.hooks.beforePrompt { _, _ -> awaitCancellation() }
            script.hooks.beforePrompt { _, _ -> "last" }
        }
        fixture.activate()
        val result = async { fixture.hooks.beforePrompt(fixture.context, "original") }
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("first\n\nlast", result.await())
        assertEquals(1_500L, testScheduler.currentTime)
    }

    @Test
    fun `after tool failures are fail open and successful additions survive timeout`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.onEvaluate = { script ->
            script.hooks.afterTool { _, _ -> error("private failure") }
            script.hooks.afterTool { _, _ -> awaitCancellation() }
            script.hooks.afterTool { _, _ -> "extra context" }
        }
        fixture.activate()
        val result = async { fixture.hooks.afterTool(fixture.call, ScriptToolResult("original")) }
        runCurrent()
        advanceTimeBy(900)
        runCurrent()
        assertEquals("extra context", result.await())
        fixture.isSessionAllowed = false
        assertNull(fixture.hooks.afterTool(fixture.call, ScriptToolResult("original")))
    }

    @Test
    fun `failed text hooks return no replacement for original content`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.onEvaluate = { script ->
            script.hooks.beforePrompt { _, _ -> error("private failure") }
            script.hooks.afterTool { _, _ -> error("private failure") }
        }
        fixture.activate()
        assertNull(fixture.hooks.beforePrompt(fixture.context, "original prompt"))
        assertNull(fixture.hooks.afterTool(fixture.call, ScriptToolResult("original tool result")))
    }

    @Test
    fun `five consecutive hook failures emit exact feedback and revoke future calls`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var calls = 0
        fixture.onEvaluate = { script ->
            script.hooks.beforeTool {
                calls++
                error("private failure")
            }
        }
        fixture.activate()
        repeat(5) {
            assertIs<ToolHookVerdict.Ask>(fixture.hooks.beforeTool(fixture.call))
            runCurrent()
        }
        assertEquals(5, calls)
        assertEquals(listOf(false, false, false, false, true), fixture.feedback.map { it.isDisabled })
        assertTrue(
            fixture.feedback.all {
                it.id == fixture.request.harness.id && it.item == fixture.request.item.id &&
                    it.revision == fixture.request.harness.revision && it.generation == fixture.request.generation
            },
        )
        assertEquals(ToolHookVerdict.Continue, fixture.hooks.beforeTool(fixture.call))
        assertEquals(5, calls)
    }

    @Test
    fun `each hook receives exact hook marker and captured send ancestry`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val captured = HarnessCallOrigin(sendChain = mapOf(HarnessId("owner") to 3))
        fixture.registrationOrigin = captured
        val observed = mutableListOf<HarnessCallOrigin?>()
        fixture.onEvaluate = { script ->
            script.hooks.beforePrompt { _, _ ->
                observed += currentCoroutineContext()[HarnessOriginContext]?.origin
                null
            }
            script.hooks.beforeTool {
                observed += currentCoroutineContext()[HarnessOriginContext]?.origin
                ToolHookVerdict.Continue
            }
            script.hooks.afterTool { _, _ ->
                observed += currentCoroutineContext()[HarnessOriginContext]?.origin
                null
            }
        }
        fixture.activate()
        fixture.hooks.beforePrompt(fixture.context, "prompt")
        fixture.hooks.beforeTool(fixture.call)
        fixture.hooks.afterTool(fixture.call, ScriptToolResult("result"))
        assertEquals<List<HarnessCallOrigin?>>(List(3) { captured.merge(HarnessCallOrigin(true)) }, observed)
        assertNull(currentCoroutineContext()[HarnessOriginContext])
    }
}
