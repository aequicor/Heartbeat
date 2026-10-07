package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.impl.data.runtime.JvmHarnessCallOrigins
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class HarnessTimerOriginTest {
    @Test
    fun `timers inherit registration restrictions without contaminating neutral timers`() = runTest {
        val origins = JvmHarnessCallOrigins()
        val fixture = HarnessTimersFixture(this, origins)
        fixture.runtime.activate()
        val observed = mutableMapOf<String, HarnessCallOrigin>()
        val restricted = HarnessCallOrigin(true, mapOf(HarnessId("ancestor") to 3))
        withContext(origins.context(restricted)) {
            fixture.timers.single().every(30.seconds) {
                observed["restricted"] = origins.current()
                assertEquals(origins.current(), currentCoroutineContext()[HarnessOriginContext]?.origin)
            }
        }
        fixture.timers.single().every(30.seconds) { observed["neutral"] = origins.current() }
        runCurrent()
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(mapOf("restricted" to restricted, "neutral" to HarnessCallOrigin()), observed)
        assertEquals(HarnessCallOrigin(), origins.current())
    }
}
