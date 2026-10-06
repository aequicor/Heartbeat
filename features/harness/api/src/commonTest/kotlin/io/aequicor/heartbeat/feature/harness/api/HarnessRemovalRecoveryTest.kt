package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test

class HarnessRemovalRecoveryTest {
    @Test
    fun `failed removal reactivates retained code in a fresh generation and ignores old runtime receipts`() {
        val live = ready.copy(
            harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Active(7)))),
            activationGeneration = 7,
        )
        val pending = live.send(HarnessIntent.Public.Delete(request, harness.id, harness.revision))
        val receipt = checkNotNull(pending.pending[harness.id]).receipt
        val restored = pending.copy(
            pending = emptyMap(),
            activationGeneration = 8,
            harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Pending(8)))),
        )
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.RemoveFailed(receipt),
            restored,
            effects = listOf(HarnessEffect.Activate(listOf(HarnessActivationRequest(harness, code, 8)))),
            outputs = listOf(HarnessOutput.StorageFailed(request)),
        )
        HarnessMachineSpec.assertIgnored(restored, HarnessIntent.Internal.ItemActivated(harness.id, code.id, 0, 7))
        HarnessMachineSpec.assertIgnored(restored, HarnessIntent.Internal.Removed(receipt))
        HarnessMachineSpec.assertIgnored(restored, HarnessIntent.Internal.RemoveFailed(receipt))
    }

    @Test
    fun `failed deletion while suspended retains content without activating code`() {
        val pending = ready.send(HarnessIntent.Public.Delete(request, harness.id, harness.revision))
            .send(HarnessIntent.Internal.Suspended)
        val receipt = checkNotNull(pending.pending[harness.id]).receipt
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.RemoveFailed(receipt),
            pending.copy(
                pending = emptyMap(),
                activationGeneration = pending.activationGeneration + 1,
                harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Disabled))),
            ),
            outputs = listOf(HarnessOutput.StorageFailed(request)),
        )
    }
}
