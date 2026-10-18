package io.aequicor.heartbeat.feature.harness.impl.domain.ownership

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipCommand
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipFence
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermit
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind

/**
 * One host-bound execution boundary. The caller persists the correlation in its protected native journal BEFORE
 * calling [admit]. Only [OwnershipAdmission.Fresh] permits one dispatch. A recovered permit never does: it may
 * represent a previously dispatched call whose response was lost. Unknown results retain their durable permit.
 * This object is host-only; neither its fence nor completion evidence may come from ScriptData or script input.
 */
internal class OwnershipExecutionGate(
    private val coordinator: OwnershipCoordinatorClient,
    private val fence: OwnershipFence,
) {
    private val log = Log.tag("OwnershipExecutionGate")

    /** Observation only. Persist this exact proposal with the native operation before attempting admission. */
    @HighFrequency
    suspend fun propose(correlation: String, kind: OwnershipPermitKind): OwnershipPermit? {
        log.v { "propose coordinated execution" }
        val read = coordinator.read(fence.key)
        val record = (read as? OwnershipRead.Current)?.record
        return if (record != null && record.accepts(fence)) {
            val existing = record.permits.singleOrNull { it.id == correlation }
            if (existing == null) {
                OwnershipPermit(correlation, record.nextPermit, fence, kind)
            } else {
                existing.takeIf { it.fence == fence && it.kind == kind }
            }
        } else {
            null
        }
    }

    @HighFrequency
    suspend fun admit(proposal: OwnershipPermit): OwnershipAdmission {
        log.v { "admit coordinated execution" }
        val read = coordinator.read(fence.key)
        val record = (read as? OwnershipRead.Current)?.record
        if (proposal.fence != fence || record?.accepts(fence) != true) return OwnershipAdmission.Unavailable
        val existing = record.permits.singleOrNull { it.id == proposal.id }
        return if (existing != null) {
            if (existing == proposal) {
                OwnershipAdmission.Recover(existing)
            } else {
                OwnershipAdmission.Unavailable
            }
        } else {
            val command = OwnershipCommand.Admit(fence.host, fence, proposal.id, proposal.sequence, proposal.kind)
            when (val change = coordinator.apply(record, command)) {
                is OwnershipChange.Current -> {
                    val permit = change.record.permits.singleOrNull { it.id == proposal.id }
                    if (change.isChanged && permit != null) {
                        OwnershipAdmission.Fresh(permit)
                    } else {
                        OwnershipAdmission.Unavailable
                    }
                }

                OwnershipChange.Conflict, is OwnershipChange.Rejected, is OwnershipChange.Unavailable ->
                    OwnershipAdmission.Unavailable
            }
        }
    }

    /**
     * Host-confirmed receipt only. Do not call from finally/cancellation handlers: coroutine cancellation is not
     * native termination. A failed settlement leaves the ledger intact and can be retried with the same receipt.
     */
    suspend fun complete(permit: OwnershipPermit): Boolean {
        log.v { "record coordinated execution completion" }
        val read = coordinator.read(fence.key)
        val record = (read as? OwnershipRead.Current)?.record
        if (permit.fence != fence || record == null) return false
        return coordinator.apply(record, OwnershipCommand.Settle(fence.host, fence, permit)) is OwnershipChange.Current
    }
}

internal sealed interface OwnershipAdmission {
    data class Fresh(val permit: OwnershipPermit) : OwnershipAdmission
    data class Recover(val permit: OwnershipPermit) : OwnershipAdmission
    data object Unavailable : OwnershipAdmission
}
