package io.aequicor.heartbeat.feature.harness.api.process

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Opaque immutable archive identity inside this profile. No private process identifiers appear in it. */
@Serializable
public data class ProcessArchiveRef(val value: String) {
    init {
        require(value.length == ARCHIVE_DIGEST_CHARS && value.all { it in '0'..'9' || it in 'a'..'f' })
    }

    override fun toString(): String = "ProcessArchiveRef(***)"
}

/** Evidence reference resolved by [ProcessArchiveReader] in the same profile, including after harness deletion. */
public fun ProcessArchiveRef.asResultRef(): ResultRef = ResultRef("process_archive", value)

/** Why a durable snapshot left the active namespace. */
@Serializable
public enum class ProcessArchiveReason {
    /** Ended process, with all external writes settled and native workflow cleanup confirmed. */
    @SerialName("completed")
    Completed,

    /** Harness deletion preserved the last observed state; this is not proof its external work stopped. */
    @SerialName("harness_deleted")
    HarnessDeleted,
}

/**
 * Protected immutable snapshot outside ScriptData quotas. Evidence references keep their original identities.
 * [record] can contain private technical state and must never be published or logged. A deleted harness's running
 * attempt is audit evidence only: restoring it never dispatches or repeats an executor or external write.
 */
@Serializable
public data class ArchivedProcess(
    val ref: ProcessArchiveRef,
    val record: ProcessRecord,
    val reason: ProcessArchiveReason,
    val archivedAt: Instant,
) {
    override fun toString(): String = "ArchivedProcess(***)"
}

/** Bounded page of archive snapshots. Pass [next] back as the next exclusive cursor. */
public data class ProcessArchivePage(val entries: List<ArchivedProcess>, val next: ProcessArchiveRef?) {
    override fun toString(): String = "ProcessArchivePage(size=${entries.size})"
}

/**
 * Profile-owned read access, including snapshots whose harness was deleted. Calls never activate harness code.
 * Data stays under the profile's SecretStore protection and is retained until that profile is explicitly wiped.
 */
public interface ProcessArchiveReader {
    /** Resolves an immutable reference, or null when no archive with that reference exists in this profile. */
    public suspend fun get(ref: ProcessArchiveRef): ArchivedProcess?

    /** Lists/searches by exact process id with bounded pages; a null cursor starts from the beginning. */
    public suspend fun list(
        after: ProcessArchiveRef? = null,
        limit: Int = 20,
        process: ProcessId? = null,
    ): ProcessArchivePage
}

private const val ARCHIVE_DIGEST_CHARS = 64
