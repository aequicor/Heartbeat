package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.event.SystemEvent
import io.aequicor.heartbeat.feature.harness.impl.data.runtime.JvmHarnessCallOrigins
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class HarnessEventOriginTest {
    @Test
    fun `queue created by restricted emitter does not contaminate subsequent neutral event`() = runTest {
        val origins = JvmHarnessCallOrigins()
        val fixture = HarnessDispatchFixture(backgroundScope, StandardTestDispatcher(testScheduler), origins)
        val observed = mutableListOf<HarnessCallOrigin>()
        fixture.onEvaluate = { script ->
            script.events.on(SystemEvent::class) {
                val origin = origins.current()
                assertEquals(origin, currentCoroutineContext()[HarnessOriginContext]?.origin)
                observed += origin
            }
        }
        fixture.activate()
        val restricted = HarnessCallOrigin(true, mapOf(HarnessId("ancestor") to 3))
        withContext(origins.context(restricted)) {
            // This first emit constructs the long-lived consumer while the producer is restricted.
            fixture.events.emit(SystemEvent.Started(Instant.fromEpochMilliseconds(1)), restricted)
        }
        runCurrent()
        assertEquals(listOf(restricted), observed)
        assertEquals(HarnessCallOrigin(), origins.current())

        fixture.events.emit(SystemEvent.Started(Instant.fromEpochMilliseconds(2)))
        runCurrent()
        assertEquals(listOf(restricted, HarnessCallOrigin()), observed)
        assertEquals(HarnessCallOrigin(), origins.current())
    }
}
