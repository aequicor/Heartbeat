package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptScope
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiCall
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessUiReviewTest {
    private val call = ScriptUiCall(JsonObject(emptyMap()))

    @Test
    fun `review reads selected source without executing and identical source reactivation invalidates it`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var calls = 0
        fixture.onEvaluate = { script ->
            script.ui.method(ItemName("record"), "Record") {
                calls++
                ScriptUiResult.Value(JsonPrimitive(calls))
            }
        }
        val dispatch = HarnessUiDispatch(fixture.runtime, fixture.sessions)
        val harness = fixture.request.harness.id
        assertNull(dispatch.review(harness, "record"))
        fixture.activate()
        val reviewed = assertNotNull(dispatch.review(harness, "record"))
        assertEquals((fixture.request.item as HarnessItem.Script).source, reviewed.source)
        assertEquals(0, calls)
        assertNull(dispatch.review(harness, "missing"))
        assertEquals(HarnessUiOutcome.Value(JsonPrimitive(1)), dispatch.call(harness, "record", call, reviewed.binding))

        fixture.desired = fixture.request.copy(generation = fixture.request.generation + 1)
        fixture.activate()
        val replacement = assertNotNull(dispatch.review(harness, "record"))
        assertNotEquals(reviewed.binding, replacement.binding)
        assertEquals(reviewed.source, replacement.source)
        assertEquals(HarnessUiOutcome.Unavailable, dispatch.call(harness, "record", call, reviewed.binding))
        assertEquals(1, calls)
        assertEquals(HarnessUiOutcome.Value(JsonPrimitive(2)), dispatch.call(harness, "record", call))
    }

    @Test
    fun `replacing a callback within an activation cannot reuse its reviewed binding`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        lateinit var running: HarnessScriptScope
        lateinit var registration: ScriptRegistration
        var calls = 0
        fixture.onEvaluate = { script ->
            running = script
            registration = script.ui.method(ItemName("record"), "Record") {
                ScriptUiResult.Value(JsonPrimitive(0))
            }
        }
        fixture.activate()
        val dispatch = HarnessUiDispatch(fixture.runtime, fixture.sessions)
        val harness = fixture.request.harness.id
        val reviewed = assertNotNull(dispatch.review(harness, "record"))
        registration.dispose()
        running.ui.method(ItemName("record"), "Replacement") {
            calls++
            ScriptUiResult.Value(JsonPrimitive(calls))
        }
        val replacement = assertNotNull(dispatch.review(harness, "record"))
        assertNotEquals(reviewed.binding, replacement.binding)
        assertEquals(HarnessUiOutcome.Unavailable, dispatch.call(harness, "record", call, reviewed.binding))
        assertEquals(0, calls)
        assertEquals(
            HarnessUiOutcome.Value(JsonPrimitive(1)),
            dispatch.call(harness, "record", call, replacement.binding),
        )
    }

    @Test
    fun `revocation after runtime admission is rechecked immediately before the callback`() = runTest {
        var beforeExecution: () -> Unit = {}
        val origins = object : HarnessCallOrigins {
            override fun current(): HarnessCallOrigin = HarnessCallOrigin()
            override fun context(origin: HarnessCallOrigin): CoroutineContext {
                beforeExecution()
                return HarnessOriginContext(origin)
            }
        }
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler), origins)
        var calls = 0
        fixture.onEvaluate = { script ->
            script.ui.method(ItemName("record"), "Record") {
                calls++
                ScriptUiResult.Value(JsonPrimitive(calls))
            }
        }
        fixture.activate()
        val dispatch = HarnessUiDispatch(fixture.runtime, fixture.sessions)
        val harness = fixture.request.harness.id
        val reviewed = assertNotNull(dispatch.review(harness, "record"))
        var hasAdmitted = false
        beforeExecution = {
            hasAdmitted = true
            fixture.isEnabled = false
        }
        assertEquals(HarnessUiOutcome.Unavailable, dispatch.call(harness, "record", call, reviewed.binding))
        assertTrue(hasAdmitted)
        assertEquals(0, calls)
    }
}
