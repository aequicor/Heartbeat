package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.process.ArchivedProcess
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchivePage
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchiveReader
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchiveReason
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchiveRef
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord

/** Protected immutable records, outside the small active namespace and independent of harness lifetime. */
internal interface HarnessProcessArchiveStorage : ProcessArchiveReader {
    suspend fun isEnabled(): Boolean
    suspend fun store(owner: HarnessId, record: ProcessRecord, reason: ProcessArchiveReason): ArchivedProcess

    /** Orphan namespaces already have the full opaque owner hash, even when legacy data has no owner field. */
    suspend fun storeOrphan(ownerHash: String, record: ProcessRecord): ArchivedProcess
    suspend fun getOwned(owner: HarnessId, ref: ProcessArchiveRef): ArchivedProcess?
    suspend fun listOwned(
        owner: HarnessId,
        after: ProcessArchiveRef?,
        limit: Int,
        process: ProcessId?,
    ): ProcessArchivePage
}
