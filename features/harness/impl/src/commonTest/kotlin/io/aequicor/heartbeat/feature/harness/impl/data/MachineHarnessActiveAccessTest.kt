package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.HarnessActivationUnavailable
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.MachineHarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.data.services.SchedulerAccessMachine
import io.aequicor.heartbeat.feature.harness.impl.data.services.SchedulerAccessRegistry
import io.aequicor.heartbeat.feature.harness.impl.data.services.SchedulerAccessToggles
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MachineHarnessActiveAccessTest {
    private val registry = SchedulerAccessRegistry()
    private val toggles = SchedulerAccessToggles()
    private val access = MachineHarnessActiveAccess(registry, toggles)

    @Test
    fun `cold startup waits for library and selects only explicit connections`() = runTest {
        val waiting = async { access.active(null, dispatchSession) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        val entry = harness.copy(scope = HarnessScope.Profile)
        registry.library.value = SchedulerAccessMachine(HarnessState.Ready(listOf(HarnessEntry(entry))))
        assertEquals(emptyList(), waiting.await())
        assertEquals(emptyList(), access.active(WorkspaceRef("project"), dispatchSession))
        registry.library.value = SchedulerAccessMachine(
            HarnessState.Ready(
                listOf(HarnessEntry(entry)),
                attachments = mapOf(dispatchSession to setOf(entry.id)),
            ),
        )
        assertEquals(listOf(entry), access.active(null, dispatchSession))
        assertEquals(listOf(entry), access.active(WorkspaceRef("project"), dispatchSession))
        assertEquals(emptyList(), access.active(null, null))
    }

    @Test
    fun `missing library times out and disabling while waiting removes policy`() = runTest {
        val unavailable = async {
            assertFailsWith<IllegalStateException> { access.active(null, dispatchSession) }
        }
        advanceTimeBy(1_001)
        unavailable.await()
        val waiting = async { access.active(null, dispatchSession) }
        runCurrent()
        toggles.enabled.value = false
        assertEquals(emptyList(), waiting.await())
        registry.observations = 0
        assertEquals(emptyList(), access.active(null, dispatchSession))
        assertEquals(0, registry.observations)
    }

    @Test
    fun `proven unavailability probes until a library read succeeds`() = runTest {
        val first = async { assertFailsWith<HarnessActivationUnavailable> { access.active(null, dispatchSession) } }
        advanceTimeBy(1_001)
        first.await()
        // One latch for every read: a read with a workspace after a failed one without it only probes.
        val probe = async {
            assertFailsWith<HarnessActivationUnavailable> { access.active(WorkspaceRef("project"), dispatchSession) }
        }
        advanceTimeBy(51)
        runCurrent()
        assertTrue(probe.isCompleted)
        val entry = harness.copy(scope = HarnessScope.Profile)
        registry.library.value = SchedulerAccessMachine(HarnessState.Ready(listOf(HarnessEntry(entry))))
        assertEquals(emptyList(), access.active(null, dispatchSession))
        registry.library.value = null
        val waiting = async { access.active(null, dispatchSession) }
        advanceTimeBy(500)
        runCurrent()
        assertFalse(waiting.isCompleted)
        registry.library.value = SchedulerAccessMachine(HarnessState.Ready(listOf(HarnessEntry(entry))))
        assertEquals(emptyList(), waiting.await())
    }

    @Test
    fun `a connection activates in any workspace including for a legacy project scope`() = runTest {
        val entry = harness.copy(scope = HarnessScope.Projects(setOf(WorkspaceRef("project"))))
        registry.library.value = SchedulerAccessMachine(
            HarnessState.Ready(
                listOf(HarnessEntry(entry)),
                attachments = mapOf(dispatchSession to setOf(entry.id)),
            ),
        )
        assertEquals(listOf(entry), access.active(WorkspaceRef("worktree"), dispatchSession))
    }

    @Test
    fun `a session being opened gets only enabled harnesses its creator chose`() = runTest {
        val disabled = harness.copy(id = HarnessId("disabled"), isEnabled = false)
        registry.library.value = SchedulerAccessMachine(
            HarnessState.Ready(listOf(HarnessEntry(harness), HarnessEntry(disabled))),
        )
        assertEquals(listOf(harness), access.opening(setOf(harness.id, disabled.id, HarnessId("missing"))))
        assertEquals(emptyList(), access.opening(emptySet()))
        assertEquals(emptyList(), access.active(null, null))
    }

    @Test
    fun `failed library fails at once as already reported unavailability`() = runTest {
        registry.library.value = SchedulerAccessMachine(HarnessState.Failed())
        assertFailsWith<HarnessActivationUnavailable> { access.active(null, dispatchSession) }
    }
}
