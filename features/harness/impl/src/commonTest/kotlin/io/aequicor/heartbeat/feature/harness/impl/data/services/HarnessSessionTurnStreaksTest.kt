package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionLifecycle
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessLimitRefusal
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.MemoryHarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.isHarnessLimitRefusal
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HarnessSessionTurnStreaksTest {
    private val harness = HarnessId("owner")
    private val ancestry = MemoryHarnessRequestAncestry()
    private val gate = HarnessEventGate().apply { open() }
    private val streaks = HarnessSessionTurnStreaks()
    private val observer = HarnessSessionTurnObserver(gate, lazyOf(ancestry), streaks)
    private var sequence = 0

    @Test
    fun `consecutive harness turns exhaust the session until a user turn`() = runTest {
        assertTrue(observer.isObserving)
        repeat(HarnessLimits.SEND_CHAIN) {
            assertFalse(streaks.isExhausted(dispatchSession))
            observer.observe(started(harnessTurn(HarnessCallOrigin(sendChain = mapOf(harness to 1)))))
        }
        assertTrue(streaks.isExhausted(dispatchSession))
        val other = SessionRef(EngineId("engine"), SessionSourceId("source"), "other")
        assertFalse(streaks.isExhausted(other))
        // A turn without request identity or with unreadable ancestry neither counts nor forgives.
        observer.observe(SessionLifecycle.TurnStarted(context(null)))
        ancestry.failure = IllegalStateException("private IO failure")
        observer.observe(started(RequestId("user")))
        assertTrue(streaks.isExhausted(dispatchSession))
        ancestry.failure = null
        observer.observe(started(RequestId("user")))
        assertFalse(streaks.isExhausted(dispatchSession))
        gate.close()
        assertFalse(observer.isObserving)
    }

    @Test
    fun `hook relays without a chain count as harness turns`() = runTest {
        repeat(HarnessLimits.SEND_CHAIN) {
            observer.observe(started(harnessTurn(HarnessCallOrigin(isHookRestricted = true))))
        }
        assertTrue(streaks.isExhausted(dispatchSession))
    }

    @Test
    fun `exhausted session refuses script wakes and sends whatever their carried origin`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        // Escaped author code can present a neutral origin; only the session streak still bounds it.
        fixture.runtime.origins.origin = HarnessCallOrigin()
        val condition = WakeCondition(events = setOf(EventKeys.custom("trigger")))
        repeat(HarnessLimits.SEND_CHAIN) { fixture.streaks.harnessTurnStarted(dispatchSession) }
        val reads = fixture.externalReads
        assertFailsWith<IllegalStateException> { fixture.script.scheduler.wake(dispatchSession, condition, "loop") }
        assertEquals(reads, fixture.externalReads)
        assertTrue(fixture.port.scheduled.isEmpty())
        fixture.streaks.otherTurnStarted(dispatchSession)
        fixture.script.scheduler.wake(dispatchSession, condition, "after user turn")
        assertEquals(1, fixture.port.scheduled.size)
    }

    @Test
    fun `streak refusals never disable a timer or a background loop that keep sending`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        var attempts = 0
        fixture.runtime.evaluate = { script ->
            script.scheduler.every(HarnessLimits.MIN_TIMER) {
                attempts++
                script.sessions.send(dispatchSession, "tick")
            }
        }
        val instance = fixture.activate()
        repeat(HarnessLimits.SEND_CHAIN) { fixture.streaks.harnessTurnStarted(dispatchSession) }
        val refusal = assertFailsWith<HarnessLimitRefusal> { fixture.script.sessions.send(dispatchSession, "direct") }
        assertTrue(refusal.isHarnessLimitRefusal())
        advanceTimeBy(HarnessLimits.MIN_TIMER * (HarnessLimits.FAILURES + 1))
        runCurrent()
        repeat(HarnessLimits.FAILURES + 1) {
            fixture.script.scope.launch {
                attempts++
                fixture.script.sessions.send(dispatchSession, "loop")
            }
        }
        runCurrent()
        assertEquals(2 * (HarnessLimits.FAILURES + 1), attempts)
        assertTrue(fixture.port.scheduled.isEmpty())
        assertSame(instance, fixture.runtime.runtime.instance(instance.request.harness.id, instance.request.item.id))
        assertEquals(0, instance.consecutiveFailures)
        // The refusal is the streak's own: after a user turn the same timer sends again.
        fixture.streaks.otherTurnStarted(dispatchSession)
        advanceTimeBy(HarnessLimits.MIN_TIMER)
        runCurrent()
        assertEquals(1, fixture.port.scheduled.size)
    }

    private suspend fun harnessTurn(origin: HarnessCallOrigin): RequestId {
        val request = RequestId("wake_${sequence++}")
        ancestry.restrict(dispatchSession, request, origin)
        return request
    }

    private fun started(request: RequestId) = SessionLifecycle.TurnStarted(context(request))

    private fun context(request: RequestId?) =
        SessionHookContext(dispatchSession, null, request, TurnId("turn"), SessionOwner("studio"))
}
