@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.WakeFailure
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import io.aequicor.heartbeat.feature.scheduler.impl.domain.OwnedWakeAdmission
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OwnedWakeAdmissionTest {
    private val request = wakeRequest("owned", events = setOf(EventKeys.custom("done")))

    @Test
    fun `each check reads current owner state and observed refusal cannot be revived`() = runTest {
        val state = MutableStateFlow(ScheduledWakeAdmission.Allow)
        val admission = OwnedWakeAdmission(owner(state), request)
        assertEquals(ScheduledWakeAdmission.Allow, admission.decisions.first())
        // No dispatcher turn for a background collector: begin must still see the current value.
        state.value = ScheduledWakeAdmission.Drop
        assertEquals(ScheduledWakeAdmission.Drop, admission.decisions.first())
        state.value = ScheduledWakeAdmission.Allow
        assertEquals(ScheduledWakeAdmission.Drop, admission.decisions.first())
    }

    @Test
    fun `live decision stream revokes an already waiting host`() = runTest {
        val state = MutableStateFlow(ScheduledWakeAdmission.Allow)
        val admission = OwnedWakeAdmission(owner(state), request)
        val observed = mutableListOf<ScheduledWakeAdmission>()
        val collection = launch { admission.decisions.collect { observed += it } }
        runCurrent()
        state.value = ScheduledWakeAdmission.Defer
        runCurrent()
        assertEquals(ScheduledWakeAdmission.Defer, observed.last())
        state.value = ScheduledWakeAdmission.Allow
        runCurrent()
        assertEquals(ScheduledWakeAdmission.Defer, observed.last())
        collection.cancel()
    }

    @Test
    fun `empty failing and silent owner decisions all refuse within the initial budget`() = runTest {
        val sources = listOf<Flow<ScheduledWakeAdmission>>(
            emptyFlow(),
            flow { error("Failed owner") },
            flow { awaitCancellation() },
        )
        for (source in sources) {
            val admission = OwnedWakeAdmission(owner(source), request)
            assertEquals(ScheduledWakeAdmission.Drop, admission.decisions.first())
            assertEquals(WakeFailure.OwnerUnavailable, admission.failure)
        }
    }

    @Test
    fun `deadline wake cannot defer into an immediate timer retry loop`() = runTest {
        val admission = OwnedWakeAdmission(
            owner(MutableStateFlow(ScheduledWakeAdmission.Defer)),
            request.copy(condition = request.condition.copy(deadline = START)),
        )
        assertEquals(ScheduledWakeAdmission.Drop, admission.decisions.first())
        assertEquals(WakeFailure.OwnerRejected, admission.failure)
    }

    private fun owner(source: Flow<ScheduledWakeAdmission>) = object : ScheduledWakeOwner {
        override val feature = "test"
        override fun admission(request: WakeRequest): Flow<ScheduledWakeAdmission> = source
    }
}
