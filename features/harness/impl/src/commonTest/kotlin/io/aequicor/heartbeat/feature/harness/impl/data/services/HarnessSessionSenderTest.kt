package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.scriptRequest
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.deliveryRequestId
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class HarnessSessionSenderTest {
    @Test
    fun `visible send preserves text and increments only this owners durable ancestry`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        val active = fixture.activate()
        val owner = HarnessInstanceTarget(active.request, active)
        val other = HarnessId("other")
        val origin = HarnessCallOrigin(sendChain = mapOf(owner.request.harness.id to 2, other to 3))
        val text = "``` quoted instructions ```"
        val id = fixture.sender.send(owner, dispatchSession, text, origin)
        val request = fixture.port.scheduled.single()
        assertEquals(id, request.id)
        assertEquals(text, request.note)
        assertTrue(request.isNoteVisible)
        assertTrue(request.isDeduplicationRequired)
        assertEquals("harness", request.ownerFeature)
        assertEquals(testScheduler.currentTime, request.condition.deadline?.toEpochMilliseconds())
        val inherited = HarnessOwnedContext().decode(request.ownerContext)?.origin()
        assertEquals(mapOf(owner.request.harness.id to 3, other to 3), inherited?.sendChain)
        assertEquals(inherited, fixture.ancestry.lookup(dispatchSession, id.deliveryRequestId()))
        assertEquals(2, origin.sendChain[owner.request.harness.id])
    }

    @Test
    fun `hooks and exhausted chains are rejected before catalog or quota IO`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        val active = fixture.activate()
        val owner = HarnessInstanceTarget(active.request, active)
        val forbidden = listOf(
            HarnessCallOrigin(isHookRestricted = true),
            HarnessCallOrigin(sendChain = mapOf(owner.request.harness.id to 3)),
        )
        for (origin in forbidden) {
            assertFailsWith<IllegalStateException> { fixture.sender.send(owner, dispatchSession, "hidden", origin) }
        }
        assertEquals(0, fixture.externalReads)
        assertTrue(fixture.port.scheduled.isEmpty())
        fixture.sender.send(owner, dispatchSession, "allowed", HarnessCallOrigin())
        assertEquals(1, fixture.port.scheduled.size)
    }

    @Test
    fun `send rate belongs to harness across items and resets only after receipt window`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        val first = fixture.activate()
        fixture.runtime.desired = scriptRequest(1, item = "second")
        val second = fixture.activate()
        val owners = listOf(first, second).map { HarnessInstanceTarget(it.request, it) }
        repeat(6) { index ->
            fixture.sender.send(owners[index % 2], dispatchSession, "message $index", HarnessCallOrigin())
            // Delivery is terminal; only the harness-wide send debit remains.
            fixture.port.ready = SchedulerState.Ready()
        }
        assertFailsWith<IllegalStateException> {
            fixture.sender.send(owners.first(), dispatchSession, "over limit", HarnessCallOrigin())
        }
        advanceTimeBy(1.minutes)
        fixture.sender.send(owners.last(), dispatchSession, "next minute", HarnessCallOrigin())
        assertEquals(7, fixture.port.scheduled.size)
    }

    @Test
    fun `visible sends share pending session capacity and obey revoked activation`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        val active = fixture.activate()
        val owner = HarnessInstanceTarget(active.request, active)
        repeat(2) { fixture.sender.send(owner, dispatchSession, "message", HarnessCallOrigin()) }
        assertFailsWith<IllegalStateException> {
            fixture.sender.send(owner, dispatchSession, "overflow", HarnessCallOrigin())
        }
        fixture.port.ready = SchedulerState.Ready()
        fixture.isAllowed = false
        assertFailsWith<IllegalStateException> {
            fixture.sender.send(owner, dispatchSession, "revoked", HarnessCallOrigin())
        }
        assertEquals(2, fixture.port.scheduled.size)
    }
}
