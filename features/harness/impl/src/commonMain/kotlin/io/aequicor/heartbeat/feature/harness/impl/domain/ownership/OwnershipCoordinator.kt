package io.aequicor.heartbeat.feature.harness.impl.domain.ownership

import io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership

/**
 * A shared, authenticated, linearizable register. A backend MUST compare the entire [ProcessOwnership] including
 * its revision atomically, and never delete or expire an entry. Conditional create uses null as expected value.
 * Profile storage and comments/labels cannot implement this contract; they must report [OwnershipUnavailable].
 * Credentials belong to the adapter, not the checkpoint. Caller identity is verified independently of payload.
 *
 * This port does not claim to fence native/external systems. A host still needs coordinator-backed admission
 * permits, drainage, stop receipts and checkpoint reconciliation before connecting this to execution.
 */
internal interface OwnershipBackend {
    suspend fun read(key: OwnershipKey): OwnershipRead

    /** Never retries after an uncertain response. Unknown means the write might have committed. */
    suspend fun compareAndSet(key: OwnershipKey, expected: ProcessOwnership?, next: ProcessOwnership): OwnershipCommit
}

internal sealed interface OwnershipRead {
    data class Current(val record: ProcessOwnership?) : OwnershipRead
    data class Unavailable(val reason: OwnershipUnavailable) : OwnershipRead
}

internal sealed interface OwnershipCommit {
    data object Applied : OwnershipCommit
    data object Conflict : OwnershipCommit
    data class Unavailable(val reason: OwnershipUnavailable) : OwnershipCommit
}

internal enum class OwnershipUnavailable {
    NotConfigured,
    AtomicCompareAndSetUnsupported,
    Transport,
    UnknownCommit,
    InvalidRecord,
}

/** Explicit absence, not an in-memory or local-profile fallback pretending to own work across hosts. */
internal class UnavailableOwnershipBackend(
    private val reason: OwnershipUnavailable = OwnershipUnavailable.NotConfigured,
) : OwnershipBackend {
    override suspend fun read(key: OwnershipKey): OwnershipRead = OwnershipRead.Unavailable(reason)

    override suspend fun compareAndSet(
        key: OwnershipKey,
        expected: ProcessOwnership?,
        next: ProcessOwnership,
    ): OwnershipCommit = OwnershipCommit.Unavailable(reason)
}
