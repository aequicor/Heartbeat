package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessLoad
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemStatus
import io.aequicor.heartbeat.feature.harness.impl.domain.code
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.receipt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessLibraryAdmissionTest {
    private val request = HarnessActivationRequest(harness, code, 1)
    private val ready = HarnessState.Ready(
        harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Pending(1)))),
        isRuntimeAvailable = true,
    )

    @Test
    fun `publication requires exact committed pending generation`() {
        var state: HarnessState = ready
        val admission = HarnessLibraryAdmission { state }
        assertTrue(admission.canPublish(request))
        assertFalse(admission.canPublish(request.copy(generation = 0)))
        assertFalse(admission.canPublish(request.copy(harness = harness.copy(revision = 1))))
        state = ready.copy(harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Active(1)))))
        assertFalse(admission.canPublish(request))
        assertTrue(admission.canInvoke(request))
        state = HarnessState.Loading(HarnessLoad(1, ready.revision))
        assertFalse(admission.canPublish(request))
        assertFalse(admission.canInvoke(request))
    }

    @Test
    fun `old code remains callable during pending or failed replacement`() {
        val changed = harness.copy(revision = 1, items = listOf(code.copy(source = "new code")))
        var state = ready.copy(harnesses = listOf(HarnessEntry(changed, mapOf(code.id to ItemStatus.Pending(2)))))
        val admission = HarnessLibraryAdmission { state }
        assertFalse(admission.canPublish(request))
        assertTrue(admission.canInvoke(request))
        state = state.copy(harnesses = listOf(HarnessEntry(changed, mapOf(code.id to ItemStatus.Failed(2, false)))))
        assertTrue(admission.canInvoke(request))
        state = state.copy(harnesses = listOf(HarnessEntry(changed, mapOf(code.id to ItemStatus.Failed(2, true)))))
        assertFalse(admission.canInvoke(request))
    }

    @Test
    fun `disable suspension and pending removal deny admission before effects run`() {
        val denied = listOf(
            ready.copy(isSuspended = true),
            ready.copy(isRuntimeAvailable = false),
            ready.copy(pending = mapOf(harness.id to HarnessMutation.Remove(receipt, harness))),
            ready.copy(
                harnesses = listOf(
                    HarnessEntry(harness.copy(isEnabled = false), ready.harnesses.first().itemStatus),
                ),
            ),
            ready.copy(
                harnesses = listOf(
                    HarnessEntry(
                        harness.copy(items = listOf(code.copy(isEnabled = false))),
                        ready.harnesses.first().itemStatus,
                    ),
                ),
            ),
            ready.copy(harnesses = emptyList()),
        )
        denied.forEach { state ->
            val admission = HarnessLibraryAdmission { state }
            assertFalse(admission.canPublish(request))
            assertFalse(admission.canInvoke(request))
        }
        val pendingSave = ready.copy(
            pending = mapOf(
                harness.id to
                    HarnessMutation.Save(receipt, harness.copy(isEnabled = false), false),
            ),
        )
        assertTrue(HarnessLibraryAdmission { pendingSave }.canInvoke(request))
    }
}
