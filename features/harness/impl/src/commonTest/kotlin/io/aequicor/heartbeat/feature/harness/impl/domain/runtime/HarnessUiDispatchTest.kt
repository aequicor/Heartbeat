package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiCall
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HarnessUiDispatchTest {
    @Test
    fun `published methods run for user actions without a turn`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<ScriptUiCall>()
        fixture.onEvaluate = { script ->
            script.ui.method(ItemName("prs"), "Pull requests") { call ->
                calls += call
                ScriptUiResult.Value(JsonPrimitive("repo ${call.arguments["repo"]?.jsonPrimitive?.content}"))
            }
        }
        fixture.activate()
        val dispatch = HarnessUiDispatch(fixture.runtime, fixture.sessions)
        val harness = fixture.request.harness.id

        assertEquals(listOf(HarnessUiMethod(ItemName("prs"), "Pull requests")), dispatch.methods(harness))
        val call = ScriptUiCall(JsonObject(mapOf("repo" to JsonPrimitive("a/b"))))
        assertEquals(HarnessUiOutcome.Value(JsonPrimitive("repo a/b")), dispatch.call(harness, "prs", call))
        assertEquals(listOf(call), calls)
        assertEquals(HarnessUiOutcome.NotFound, dispatch.call(harness, "other", call))
        assertEquals(HarnessUiOutcome.NotFound, dispatch.call(HarnessId("elsewhere"), "prs", call))
        assertEquals(emptyList(), dispatch.methods(HarnessId("elsewhere")))
    }

    @Test
    fun `calls in a chat need the harness to be active there`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var calls = 0
        fixture.onEvaluate = { script ->
            script.ui.method(ItemName("prs"), "Pull requests") {
                calls++
                ScriptUiResult.Value(JsonPrimitive(calls))
            }
        }
        fixture.activate()
        val dispatch = HarnessUiDispatch(fixture.runtime, fixture.sessions)
        val harness = fixture.request.harness.id
        val inChat = ScriptUiCall(JsonObject(emptyMap()), session = dispatchSession)

        val detached = ScriptUiCall(JsonObject(emptyMap()))

        assertEquals(HarnessUiOutcome.Value(JsonPrimitive(1)), dispatch.call(harness, "prs", inChat))
        fixture.isSessionAllowed = false
        assertEquals(HarnessUiOutcome.Inactive, dispatch.call(harness, "prs", inChat))
        assertEquals(HarnessUiOutcome.Value(JsonPrimitive(2)), dispatch.call(harness, "prs", detached))
        fixture.isEnabled = false
        assertEquals(HarnessUiOutcome.NotFound, dispatch.call(harness, "prs", detached))
        assertEquals(emptyList(), dispatch.methods(harness))
        assertEquals(2, calls)
    }

    @Test
    fun `expected errors failures and oversized values are told apart`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.onEvaluate = { script ->
            script.ui.method(ItemName("login"), "Needs login") { ScriptUiResult.Error("x".repeat(1_000)) }
            script.ui.method(ItemName("broken"), "Throws") { error("author bug") }
            script.ui.method(ItemName("huge"), "Too much") {
                ScriptUiResult.Value(JsonPrimitive("x".repeat(HarnessLimits.UI_RESULT_BYTES)))
            }
        }
        val instance = fixture.activate()
        val dispatch = HarnessUiDispatch(fixture.runtime, fixture.sessions)
        val harness = fixture.request.harness.id
        val call = ScriptUiCall(JsonObject(emptyMap()))

        val error = dispatch.call(harness, "login", call)
        assertEquals(HarnessUiOutcome.UserError("x".repeat(HarnessLimits.UI_ERROR_CHARS)), error)
        assertEquals(HarnessUiOutcome.TooLarge, dispatch.call(harness, "huge", call))
        assertEquals(0, instance.consecutiveFailures)

        assertEquals(HarnessUiOutcome.Failed, dispatch.call(harness, "broken", call))
        assertEquals(1, instance.consecutiveFailures)
    }

    @Test
    fun `leaving the surface cancels the running method`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        fixture.onEvaluate = { script ->
            script.ui.method(ItemName("slow"), "Slow") {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        fixture.activate()
        val dispatch = HarnessUiDispatch(fixture.runtime, fixture.sessions)
        val call = async { dispatch.call(fixture.request.harness.id, "slow", ScriptUiCall(JsonObject(emptyMap()))) }
        entered.await()

        call.cancel()
        runCurrent()

        cancelled.await()
        assertTrue(call.isCancelled)
    }

    @Test
    fun `names are unique and the method count is bounded`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.onEvaluate = { script ->
            val first = script.ui.method(ItemName("a"), "A") { ScriptUiResult.Value(JsonPrimitive(1)) }
            assertFailsWith<IllegalStateException> {
                script.ui.method(ItemName("a"), "Again") { ScriptUiResult.Value(JsonPrimitive(2)) }
            }
            first.dispose()
            script.ui.method(ItemName("a"), "Again") { ScriptUiResult.Value(JsonPrimitive(2)) }
            repeat(HarnessLimits.UI_METHODS - 1) { index ->
                script.ui.method(ItemName("m$index"), "M") { ScriptUiResult.Value(JsonPrimitive(index)) }
            }
            assertFailsWith<IllegalStateException> {
                script.ui.method(ItemName("extra"), "Extra") { ScriptUiResult.Value(JsonPrimitive(0)) }
            }
        }
        fixture.activate()
        val dispatch = HarnessUiDispatch(fixture.runtime, fixture.sessions)
        val harness = fixture.request.harness.id

        assertEquals(HarnessLimits.UI_METHODS, dispatch.methods(harness).size)
        val again = dispatch.call(harness, "a", ScriptUiCall(JsonObject(emptyMap())))
        assertEquals(HarnessUiOutcome.Value(JsonPrimitive(2)), again)
    }
}
