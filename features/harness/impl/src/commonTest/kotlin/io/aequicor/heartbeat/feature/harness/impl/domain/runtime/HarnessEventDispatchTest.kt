package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.event.HarnessEvent
import io.aequicor.heartbeat.feature.harness.api.event.SessionEvent
import io.aequicor.heartbeat.feature.harness.api.event.SystemEvent
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class HarnessEventDispatchTest {
    @Test
    fun `blocked consumer retains only newest sixty four queued events`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<Long>()
        fixture.onEvaluate = { script ->
            script.events.on(SystemEvent::class) { event ->
                received += event.at.toEpochMilliseconds()
                if (received.size == 1) release.await()
            }
        }
        fixture.activate()
        fixture.events.emit(started(0))
        runCurrent()
        repeat(70) { fixture.events.emit(started(it + 1)) }
        assertEquals(listOf(0L), received)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(0L) + (7L..70L), received)
    }

    @Test
    fun `supertype subscription matches concrete event and unrelated subtype does not`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val observed = mutableListOf<String>()
        fixture.onEvaluate = { script ->
            script.events.on(HarnessEvent::class) { observed += "base" }
            script.events.on(SystemEvent::class) { observed += "system" }
            script.events.on(SessionEvent::class) { observed += "session" }
        }
        fixture.activate()
        fixture.events.emit(started(1))
        runCurrent()
        assertEquals(listOf("base", "system"), observed)
    }

    @Test
    fun `disposal after enqueue prevents callback acquisition`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var registration: ScriptRegistration? = null
        var calls = 0
        fixture.onEvaluate = { registration = it.events.on(HarnessEvent::class) { calls++ } }
        fixture.activate()
        fixture.events.emit(started(1))
        checkNotNull(registration).dispose()
        runCurrent()
        assertEquals(0, calls)
    }

    @Test
    fun `disable and session detachment after enqueue are checked before author code`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var calls = 0
        fixture.onEvaluate = { it.events.on(HarnessEvent::class) { calls++ } }
        fixture.activate()
        fixture.events.emit(SessionEvent.Opened(fixture.context, Instant.fromEpochMilliseconds(1)))
        fixture.isSessionAllowed = false
        runCurrent()
        assertEquals(0, calls)
        fixture.isSessionAllowed = true
        fixture.events.emit(started(2))
        fixture.isEnabled = false
        runCurrent()
        assertEquals(0, calls)
    }

    @Test
    fun `registration origin combines with trusted envelope and cannot lose hook restriction`() = runTest {
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val owner = HarnessId("owner")
        fixture.registrationOrigin = HarnessCallOrigin(true, mapOf(owner to 2))
        var observed: HarnessCallOrigin? = null
        fixture.onEvaluate = { script ->
            script.events.on(HarnessEvent::class) { observed = currentCoroutineContext()[HarnessOriginContext]?.origin }
        }
        fixture.activate()
        fixture.events.emit(started(1), HarnessCallOrigin(sendChain = mapOf(owner to 4)))
        runCurrent()
        assertEquals(HarnessCallOrigin(true, mapOf(owner to 4)), observed)
        assertTrue(checkNotNull(observed).isHookRestricted)
        assertFalse(fixture.registrationOrigin.isHookRestricted)
    }
}

private fun started(index: Int): SystemEvent.Started =
    SystemEvent.Started(Instant.fromEpochMilliseconds(index.toLong()))
