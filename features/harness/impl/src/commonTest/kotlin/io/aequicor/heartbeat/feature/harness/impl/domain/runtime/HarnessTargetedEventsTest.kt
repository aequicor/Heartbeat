package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.event.HarnessEvent
import io.aequicor.heartbeat.feature.harness.api.event.SessionEvent
import io.aequicor.heartbeat.feature.harness.api.event.SystemEvent
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class HarnessTargetedEventsTest {
    @Test
    fun `targeted lifecycle event reaches only exact instance while broadcast still reaches peers`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val observed = mutableListOf<Pair<HarnessId, HarnessCallOrigin?>>()
        fixture.evaluate = { script ->
            script.events.on(SystemEvent::class) {
                observed += script.harness to currentCoroutineContext()[HarnessOriginContext]?.origin
            }
        }
        val first = fixture.activate()
        fixture.desired = scriptRequest(1, owner = "second")
        val second = fixture.activate()
        val events = HarnessEventDispatch(fixture.runtime, HarnessSessionAdmission { _, _ -> true })
        val origin = HarnessCallOrigin(true, mapOf(HarnessId("ancestor") to 2))
        val event = SystemEvent.Started(Instant.fromEpochMilliseconds(1))
        events.emitTo(second, event, origin)
        runCurrent()
        assertEquals<List<Pair<HarnessId, HarnessCallOrigin?>>>(
            listOf(second.request.harness.id to origin),
            observed,
        )
        observed.clear()
        events.emit(event)
        runCurrent()
        assertEquals(setOf(first.request.harness.id, second.request.harness.id), observed.map { it.first }.toSet())
        assertEquals(2, observed.size)
    }

    @Test
    fun `targeting retired generation never redirects event to replacement`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val revisions = mutableListOf<Long>()
        fixture.evaluate = { script -> script.events.on(SystemEvent::class) { revisions += script.revision } }
        val old = fixture.activate()
        fixture.desired = scriptRequest(2)
        val replacement = fixture.activate()
        val events = HarnessEventDispatch(fixture.runtime, HarnessSessionAdmission { _, _ -> true })
        val event = SystemEvent.Started(Instant.fromEpochMilliseconds(1))
        events.emitTo(old, event)
        runCurrent()
        assertEquals(emptyList(), revisions)
        events.emitTo(replacement, event)
        runCurrent()
        assertEquals(listOf(2L), revisions)
    }

    @Test
    fun `targeted queue rechecks session admission registration disposal and disable`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var registration: ScriptRegistration? = null
        var calls = 0
        fixture.onEvaluate = { script -> registration = script.events.on(HarnessEvent::class) { calls++ } }
        val instance = fixture.activate()
        val event = SessionEvent.Opened(fixture.context, Instant.fromEpochMilliseconds(1))
        fixture.events.emitTo(instance, event)
        fixture.isSessionAllowed = false
        runCurrent()
        assertEquals(0, calls)
        fixture.isSessionAllowed = true
        fixture.events.emitTo(instance, event)
        fixture.isEnabled = false
        runCurrent()
        assertEquals(0, calls)
        fixture.isEnabled = true
        fixture.events.emitTo(instance, event)
        checkNotNull(registration).dispose()
        runCurrent()
        assertEquals(0, calls)
    }
}
