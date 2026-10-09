package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.MachineHarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessProjectSnapshots
import io.aequicor.heartbeat.feature.harness.impl.data.services.SchedulerAccessMachine
import io.aequicor.heartbeat.feature.harness.impl.data.services.SchedulerAccessRegistry
import io.aequicor.heartbeat.feature.harness.impl.data.services.SchedulerAccessToggles
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MachineHarnessActiveAccessTest {
    private val registry = SchedulerAccessRegistry()
    private val toggles = SchedulerAccessToggles()
    private val access = MachineHarnessActiveAccess(registry, toggles, HarnessProjectSnapshots())

    @Test
    fun `cold startup waits for committed library without requiring desktop runtime`() = runTest {
        val waiting = async { access.active(null, null) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        val entry = harness.copy(scope = HarnessScope.Profile)
        registry.library.value = SchedulerAccessMachine(HarnessState.Ready(listOf(HarnessEntry(entry))))
        assertEquals(listOf(entry), waiting.await())
    }

    @Test
    fun `missing evidence times out as unavailable and disabling while waiting removes policy`() = runTest {
        val unavailable = async {
            assertFailsWith<IllegalStateException> { access.active(null, null) }
        }
        advanceTimeBy(1_001)
        unavailable.await()
        val waiting = async { access.active(null, null) }
        runCurrent()
        toggles.enabled.value = false
        assertEquals(emptyList(), waiting.await())
        registry.observations = 0
        assertEquals(emptyList(), access.active(null, null))
        assertEquals(0, registry.observations)
    }
}
