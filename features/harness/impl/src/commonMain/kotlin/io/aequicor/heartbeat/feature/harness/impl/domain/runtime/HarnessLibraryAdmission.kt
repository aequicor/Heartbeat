package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemStatus

/**
 * Reads the already committed machine state without awaiting effect delivery. A pending save leaves the old
 * revision usable; a committed replacement keeps old code available until the runtime swaps it atomically.
 * Removal, suspension and disable close admission immediately, even before their cleanup effects start.
 */
internal class HarnessLibraryAdmission(private val state: () -> HarnessState) : HarnessRuntimeAdmission {
    override fun canPublish(request: HarnessActivationRequest): Boolean {
        val entry = admittedEntry(request) ?: return false
        return entry.harness == request.harness &&
            entry.harness.items.any { it == request.item } &&
            entry.itemStatus[request.item.id] == ItemStatus.Pending(request.generation)
    }

    override fun canInvoke(request: HarnessActivationRequest): Boolean {
        val entry = admittedEntry(request) ?: return false
        val status = entry.itemStatus[request.item.id]
        return status is ItemStatus.Active || status is ItemStatus.Pending ||
            (status is ItemStatus.Failed && !status.isDisabled)
    }

    override fun canRemoveCached(request: HarnessActivationRequest): Boolean {
        val current = state() as? HarnessState.Ready ?: return false
        val harness = current.harnesses.find { it.harness.id == request.harness.id }?.harness
        return harness?.items?.none { it.id == request.item.id } != false
    }

    override fun removalGeneration(effect: HarnessEffect.Remove): Long? {
        val current = state() as? HarnessState.Ready ?: return null
        val removal = current.pending[effect.harness.id] as? HarnessMutation.Remove
        return current.activationGeneration.takeIf {
            removal != null && removal.receipt == effect.receipt && removal.harness == effect.harness
        }
    }

    private fun admittedEntry(request: HarnessActivationRequest): HarnessEntry? {
        val current = state() as? HarnessState.Ready ?: return null
        if (current.isSuspended || !current.isRuntimeAvailable ||
            current.pending[request.harness.id] is HarnessMutation.Remove
        ) {
            return null
        }
        val entry = current.harnesses.find {
            it.harness.id == request.harness.id && it.harness.isEnabled
        } ?: return null
        val item = entry.harness.items.find { it.id == request.item.id && it.isEnabled } ?: return null
        return entry.takeIf {
            (item is HarnessItem.Script && request.item is HarnessItem.Script) ||
                (item is HarnessItem.Workflow && request.item is HarnessItem.Workflow)
        }
    }
}
