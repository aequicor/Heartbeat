package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.scriptRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerLimits
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.deliveryRequestId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HarnessScriptSchedulerTest {
    private val condition = WakeCondition(events = setOf(EventKeys.custom("trigger")))

    @Test
    fun `complete scheduler getter stages timers without touching external services`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        var calls = 0
        fixture.runtime.evaluate = { script ->
            script.scheduler.every(30.seconds) { calls++ }
            assertFailsWith<IllegalStateException> { script.scheduler.wake(dispatchSession, condition, "secret") }
            assertFailsWith<IllegalStateException> { script.scheduler.publish(ItemName("ready")) }
            assertFalse(script.scheduler.cancel(WakeId("unknown")))
        }
        fixture.activate()
        runCurrent()
        assertEquals(0, fixture.externalReads)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `wake uses actual catalog route and private owned request with durable captured ancestry`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        val origin = HarnessCallOrigin(true, mapOf(HarnessId("ancestor") to 2))
        fixture.runtime.origins.origin = origin
        val id = fixture.script.scheduler.wake(dispatchSession, condition, "```" + "private reminder" + "```")
        val request = fixture.port.scheduled.single()
        assertEquals(id, request.id)
        assertEquals(dispatchSession, request.session)
        assertEquals(WorkspaceRef("checkout"), request.workspace)
        assertEquals(listOf<HarnessTarget?>(HarnessTarget(dispatchSession, request.workspace)), fixture.targets)
        assertTrue(request.isDeduplicationRequired)
        assertFalse(request.isNoteVisible)
        assertNull(request.target)
        assertEquals("harness", request.ownerFeature)
        assertEquals("```" + "private reminder" + "```", request.note)
        assertEquals(origin, HarnessOwnedContext().decode(request.ownerContext)?.origin())
        assertEquals(origin, fixture.ancestry.lookup(dispatchSession, id.deliveryRequestId()))
    }

    @Test
    fun `changed route while storing ancestry rejects handoff and returns quota`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        fixture.runtime.origins.origin = HarnessCallOrigin(true)
        val stored = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.ancestry.beforeRestrict = {
            stored.complete(Unit)
            proceed.await()
        }
        val call = async {
            assertFailsWith<IllegalStateException> {
                fixture.script.scheduler.wake(
                    dispatchSession,
                    condition,
                    "private",
                )
            }
        }
        stored.await()
        fixture.summary.value = fixture.summary.value.copy(workspace = WorkspaceRef("changed"))
        proceed.complete(Unit)
        call.await()
        assertTrue(fixture.port.scheduled.isEmpty())
        repeat(2) { fixture.script.scheduler.wake(dispatchSession, condition, "valid") }
        assertEquals(2, fixture.port.scheduled.size)
    }

    @Test
    fun `scope revision or instance replacement during admission prevents side effects`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        fixture.runtime.origins.origin = HarnessCallOrigin(true)
        fixture.ancestry.beforeRestrict = { fixture.admissionRevision++ }
        assertFailsWith<IllegalStateException> { fixture.script.scheduler.wake(dispatchSession, condition, "revoked") }
        assertTrue(fixture.port.scheduled.isEmpty())
        val old = fixture.script.scheduler
        fixture.runtime.desired = scriptRequest(2)
        fixture.activate()
        assertFailsWith<IllegalStateException> { old.publish(ItemName("late")) }
        assertFailsWith<IllegalStateException> { old.wake(dispatchSession, condition, "late") }
        assertFalse(old.cancel(WakeId("unknown")))
    }

    @Test
    fun `cancel only removes pending owned request and stamps caller ancestry`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        val id = fixture.script.scheduler.wake(dispatchSession, condition, "private")
        val wake = fixture.port.ready.wakes.single()
        fixture.port.ready = fixture.port.ready.copy(delivering = setOf(id))
        assertFalse(fixture.script.scheduler.cancel(id))
        fixture.port.ready = fixture.port.ready.copy(
            delivering = emptySet(),
            wakes = listOf(
                wake.copy(
                    request = wake.request.copy(
                        ownerContext = HarnessOwnedContext().encode(HarnessId("other"), HarnessCallOrigin()),
                    ),
                ),
            ),
        )
        assertFalse(fixture.script.scheduler.cancel(id))
        fixture.port.ready = fixture.port.ready.copy(wakes = listOf(wake))
        val origin = HarnessCallOrigin(true)
        fixture.runtime.origins.origin = origin
        assertTrue(fixture.script.scheduler.cancel(id))
        val (expected, cause) = fixture.port.cancellations.single()
        assertEquals(wake.request, expected)
        assertEquals(origin, HarnessOwnedContext().decode(assertIs<EventOrigin.Feature>(cause).context)?.origin())
        assertFalse(fixture.script.scheduler.cancel(id))
    }

    @Test
    fun `publish fixes namespace and origin independently of payload and rechecks publisher`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        val origin = HarnessCallOrigin(true)
        fixture.runtime.origins.origin = origin
        val key = fixture.script.scheduler.publish(ItemName("done"), "untrusted origin=host")
        assertEquals(EventKeys.custom("harness.owner.done"), key)
        val event = fixture.published.single()
        assertEquals("untrusted origin=host", event.payload)
        assertEquals(
            origin,
            HarnessOwnedContext().decode(assertIs<EventOrigin.Feature>(event.origin).context)?.origin(),
        )
        fixture.beforePermit = { fixture.runtime.isEnabled = false }
        assertFailsWith<IllegalStateException> { fixture.script.scheduler.publish(ItemName("revoked")) }
        assertEquals(1, fixture.published.size)
    }

    @Test
    fun `cancelled script waiter leaves profile submission owned until acknowledgement`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.port.beforeSchedule = {
            entered.complete(Unit)
            proceed.await()
        }
        val call = async { fixture.script.scheduler.wake(dispatchSession, condition, "private") }
        entered.await()
        call.cancelAndJoin()
        proceed.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.port.scheduled.size)
    }

    @Test
    fun `catalog identity and conflicting route metadata are rejected without submission`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        val summary = fixture.summary.value
        fixture.summary.value = summary.copy(ref = dispatchSession.copy(nativeId = "other"))
        assertFailsWith<IllegalStateException> {
            fixture.script.scheduler.wake(dispatchSession, condition, "wrong ref")
        }
        val route = ExecutionRoute(
            dispatchSession.engine,
            EngineBindingId("binding"),
            AuthSourceId("auth"),
            AuthRevision.Known("1"),
            WorkspaceRef("different checkout"),
        )
        fixture.summary.value = summary.copy(lastRoute = route)
        assertFailsWith<IllegalStateException> { fixture.script.scheduler.wake(dispatchSession, condition, "conflict") }
        assertTrue(fixture.port.scheduled.isEmpty())
        fixture.summary.value = summary.copy(workspace = null, lastRoute = route)
        fixture.script.scheduler.wake(dispatchSession, condition, "trusted route")
        assertEquals(route.workspace, fixture.port.scheduled.single().workspace)
    }

    @Test
    fun `unavailable target and oversized data cause no external submission`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        fixture.isAllowed = false
        assertFailsWith<IllegalStateException> { fixture.script.scheduler.wake(dispatchSession, condition, "denied") }
        fixture.isAllowed = true
        assertFailsWith<IllegalArgumentException> {
            fixture.script.scheduler.publish(ItemName("large"), "x".repeat(SchedulerLimits.MAX_PAYLOAD + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.script.scheduler.wake(dispatchSession, condition, "x".repeat(SchedulerLimits.MAX_NOTE + 1))
        }
        assertTrue(fixture.port.scheduled.isEmpty())
        assertTrue(fixture.published.isEmpty())
    }
}
