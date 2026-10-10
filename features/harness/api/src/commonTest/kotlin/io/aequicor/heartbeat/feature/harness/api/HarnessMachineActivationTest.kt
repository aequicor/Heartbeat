package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessMachineActivationTest {
    private val pending = ready.copy(
        harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Pending(1)))),
        activationGeneration = 1,
    )

    @Test
    fun `activation success and failure require exact revision item and generation`() {
        val activated = HarnessIntent.Internal.ItemActivated(harness.id, code.id, 0, 1)
        val active = pending.copy(harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Active(1)))))
        HarnessMachineSpec.assertTransition(
            pending,
            activated,
            active,
            outputs = listOf(HarnessOutput.ItemActivated(harness.id, code.id, 0)),
        )
        val failed = pending.copy(
            harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Failed(1, false)))),
        )
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.ItemActivationFailed(harness.id, code.id, 0, 1),
            failed,
            outputs = listOf(HarnessOutput.ItemFailed(harness.id, code.id, 0)),
        )
        HarnessMachineSpec.assertIgnored(pending, activated.copy(revision = 4))
        HarnessMachineSpec.assertIgnored(pending, activated.copy(generation = 4))
        HarnessMachineSpec.assertIgnored(pending, activated.copy(item = ItemId("foreign")))
        HarnessMachineSpec.assertIgnored(active, activated)
    }

    @Test
    fun `a failed activation batch settles matching items without reviving stale generations`() {
        val item = HarnessActivationRequest(harness, code, 1)
        val effect = HarnessEffect.Activate(listOf(item))
        val intent = HarnessIntent.Internal.ActivationBatchFailed(listOf(item))
        assertEquals(intent, HarnessMachineSpec.onEffectFailure(effect, IllegalStateException("private source")))
        val failed = pending.copy(
            harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Failed(1, false)))),
        )
        HarnessMachineSpec.assertTransition(
            pending,
            intent,
            failed,
            outputs = listOf(HarnessOutput.RuntimeFailed(listOf(item))),
        )
        HarnessMachineSpec.assertIgnored(failed, intent)
        HarnessMachineSpec.assertIgnored(pending, intent.copy(items = listOf(item.copy(generation = 2))))
    }

    @Test
    fun `runtime failures can disable only an exact live instance`() {
        val active = pending.send(HarnessIntent.Internal.ItemActivated(harness.id, code.id, 0, 1))
        val error = HarnessIntent.Internal.ItemRuntimeFailed(harness.id, code.id, 0, 1, false)
        val failed = active.copy(
            harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Failed(1, false)))),
        )
        HarnessMachineSpec.assertTransition(
            active,
            error,
            failed,
            outputs = listOf(HarnessOutput.ItemFailed(harness.id, code.id, 0)),
        )
        val disabled = failed.copy(
            harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Failed(1, true)))),
        )
        HarnessMachineSpec.assertTransition(
            failed,
            error.copy(isDisabled = true),
            disabled,
            effects = listOf(
                HarnessEffect.Deactivate(listOf(HarnessActivationRequest(harness, code, 1)), false, generation = 1),
            ),
            outputs = listOf(HarnessOutput.ItemFailed(harness.id, code.id, 0)),
        )
        HarnessMachineSpec.assertIgnored(disabled, error)
        HarnessMachineSpec.assertIgnored(active, error.copy(generation = 2))
        HarnessMachineSpec.assertIgnored(pending, error.copy(generation = 2))
    }

    @Test
    fun `published runtime failure precedes activation receipt without allowing a late active status`() {
        for (isDisabled in listOf(false, true)) {
            val error = HarnessIntent.Internal.ItemRuntimeFailed(harness.id, code.id, 0, 1, isDisabled)
            val failed = pending.copy(
                harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Failed(1, isDisabled)))),
            )
            val effects = if (isDisabled) {
                listOf(
                    HarnessEffect.Deactivate(listOf(HarnessActivationRequest(harness, code, 1)), false, generation = 1),
                )
            } else {
                emptyList()
            }
            HarnessMachineSpec.assertTransition(
                pending,
                error,
                failed,
                effects = effects,
                outputs = listOf(HarnessOutput.ItemFailed(harness.id, code.id, 0)),
            )
            HarnessMachineSpec.assertIgnored(failed, HarnessIntent.Internal.ItemActivated(harness.id, code.id, 0, 1))
            HarnessMachineSpec.assertIgnored(pending, error.copy(generation = 2))
            HarnessMachineSpec.assertIgnored(pending, error.copy(revision = 2))
        }
    }

    @Test
    fun `suspension preserves writes and fences late activation across resume of same revision`() {
        val write = pending.send(HarnessIntent.Public.SetEnabled(request, harness.id, true, 0, now))
        val suspended = write.copy(
            isSuspended = true,
            activationGeneration = 2,
            harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Disabled))),
        )
        HarnessMachineSpec.assertTransition(
            write,
            HarnessIntent.Internal.Suspended,
            suspended,
            effects = listOf(
                HarnessEffect.Deactivate(
                    listOf(HarnessActivationRequest(harness, code, 1)),
                    false,
                    setOf(harness.id),
                    generation = 2,
                ),
            ),
        )
        val resumed = suspended.copy(
            isSuspended = false,
            activationGeneration = 3,
            harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Pending(3)))),
        )
        HarnessMachineSpec.assertTransition(
            suspended,
            HarnessIntent.Internal.Resumed,
            resumed,
            effects = listOf(HarnessEffect.Activate(listOf(HarnessActivationRequest(harness, code, 3)))),
        )
        HarnessMachineSpec.assertIgnored(resumed, HarnessIntent.Internal.ItemActivated(harness.id, code.id, 0, 1))
        HarnessMachineSpec.assertIgnored(resumed, HarnessIntent.Internal.Resumed)
        HarnessMachineSpec.assertIgnored(suspended, HarnessIntent.Internal.Suspended)
        val saved = suspended.send(HarnessIntent.Internal.Saved(saveOf(suspended).receipt))
        assertEquals(ItemStatus.Disabled, saved.harnesses.single().itemStatus[code.id])
        assertEquals(
            emptyList(),
            HarnessMachineSpec.resolve(suspended, HarnessIntent.Internal.Saved(saveOf(suspended).receipt))?.effects,
        )
    }

    @Test
    fun `disabling a harness cancels pinned runs even after its last workflow was removed`() {
        val empty = ready.copy(harnesses = listOf(HarnessEntry(harness.copy(items = emptyList()))))
        val saving = empty.send(HarnessIntent.Public.SetEnabled(request, harness.id, false, 0, now))
        val receipt = saveOf(saving).receipt
        val committed = saving.send(HarnessIntent.Internal.Saved(receipt))
        HarnessMachineSpec.assertTransition(
            saving,
            HarnessIntent.Internal.Saved(receipt),
            committed,
            effects = listOf(HarnessEffect.Deactivate(emptyList(), true, setOf(harness.id), generation = 1)),
            outputs = listOf(HarnessOutput.Updated(request, harness.id)),
        )
        val suspended = empty.send(HarnessIntent.Internal.Suspended)
        HarnessMachineSpec.assertTransition(
            empty,
            HarnessIntent.Internal.Suspended,
            suspended,
            effects = listOf(HarnessEffect.Deactivate(emptyList(), false, setOf(harness.id), generation = 1)),
        )
    }

    @Test
    fun `failed replacement deactivation fences the previous live generation too`() {
        val failed = pending.send(HarnessIntent.Internal.ItemActivationFailed(harness.id, code.id, 0, 1))
        val suspended = failed.send(HarnessIntent.Internal.Suspended)
        HarnessMachineSpec.assertTransition(
            failed,
            HarnessIntent.Internal.Suspended,
            suspended,
            effects = listOf(
                HarnessEffect.Deactivate(
                    listOf(HarnessActivationRequest(harness, code, 1)),
                    false,
                    setOf(harness.id),
                    generation = 2,
                ),
            ),
        )
        // Deactivate's inclusive generation fence covers a retained older instance; a future generation is excluded.
        val resumed = suspended.send(HarnessIntent.Internal.Resumed)
        assertEquals(ItemStatus.Pending(3), resumed.harnesses.single().itemStatus[code.id])
    }
}
