package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessApproval
import io.aequicor.heartbeat.feature.harness.api.HarnessApprovalWrite
import io.aequicor.heartbeat.feature.harness.api.HarnessAttachmentWrite
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessReceipt

/** A coherent committed snapshot; raw repository data never escapes this port. */
internal data class HarnessLibrarySnapshot(
    val harnesses: List<Harness> = emptyList(),
    val attachments: Map<SessionRef, Set<HarnessId>> = emptyMap(),
    val approval: HarnessApproval = HarnessApproval.Ask,
    val approvalRevision: Long = 0,
)

/**
 * Profile library persistence. Every operation first recovers its private journal under the repository lock.
 * Mutation success echoes exactly the supplied receipt. A regular failure means the old state is proven intact;
 * [HarnessStorageUncertain] means neither success nor rollback can yet be confirmed, so keep the machine mutation
 * pending and retry the exact command. Delete covers library data and attachments; runtime cancellation belongs
 * to the effect handler. No scripts or workflow drivers execute from this port.
 */
internal interface HarnessLibraryStorage {
    suspend fun load(): HarnessLibrarySnapshot
    suspend fun save(harness: Harness, receipt: HarnessReceipt): HarnessReceipt
    suspend fun remove(harness: Harness, receipt: HarnessReceipt): HarnessReceipt
    suspend fun saveAttachments(write: HarnessAttachmentWrite): HarnessAttachmentWrite
    suspend fun saveApproval(write: HarnessApprovalWrite): HarnessApprovalWrite
}

/** Sanitized repository failures; none retain an original user-controlled message or cause. */
internal sealed class HarnessStorageException(message: String) : IllegalStateException(message)

/** A failed write with confirmed rollback. Never contains user JSON, exception messages or causes. */
internal class HarnessStorageFailure : HarnessStorageException("Harness storage write rolled back")

/** No commit/rollback proof yet. The effect must retain its pending mutation and retry the exact operation. */
internal class HarnessStorageUncertain : HarnessStorageException("Harness storage outcome is uncertain")

/** Raw records or their cross-record invariants could not be decoded. Never substitute defaults for corruption. */
internal class HarnessStorageCorrupt(kind: String = "Record") :
    HarnessStorageException(
        "Harness storage is unreadable ($kind)",
    )

/** The proposal does not match the current committed revision, immutable identity or quotas. */
internal class HarnessStorageConflict :
    HarnessStorageException(
        "Harness storage proposal conflicts with committed data",
    )
