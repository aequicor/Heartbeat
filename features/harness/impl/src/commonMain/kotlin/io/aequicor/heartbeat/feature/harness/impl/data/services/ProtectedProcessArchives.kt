package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.process.ArchivedProcess
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchivePage
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchiveReader
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchiveReason
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchiveRef
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessLimits
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDurableProcessesEnabled
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessProcessArchiveStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock

/**
 * Each snapshot is one protected immutable secret. The full owner hash is checked inside the protected envelope;
 * its prefix only narrows listing. No separate index can become inconsistent with records. Retention is permanent
 * until profile wipe; explicit capacity failure preserves the active original instead of evicting evidence.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<HarnessProcessArchiveStorage>())
@ContributesBinding(ProfileScope::class, binding = binding<ProcessArchiveReader>())
@Inject
internal class ProtectedProcessArchives(
    private val secrets: SecretStore,
    private val clock: Clock,
    private val toggles: FeatureToggles,
) : HarnessProcessArchiveStorage {
    private val mutex = Mutex()
    private val log = Log.tag("HarnessProcessArchive")

    override suspend fun isEnabled(): Boolean = toggles.get(HarnessDurableProcessesEnabled)

    override suspend fun store(
        owner: HarnessId,
        record: ProcessRecord,
        reason: ProcessArchiveReason,
    ): ArchivedProcess = storeHashed(owner.value.encodeUtf8().sha256().hex(), record, reason)

    override suspend fun storeOrphan(ownerHash: String, record: ProcessRecord): ArchivedProcess =
        storeHashed(ownerHash, record, ProcessArchiveReason.HarnessDeleted)

    private suspend fun storeHashed(
        ownerHash: String,
        record: ProcessRecord,
        reason: ProcessArchiveReason,
    ): ArchivedProcess = mutex.withLock {
        require(isDigest(ownerHash)) { "Invalid archive owner" }
        require(
            reason != ProcessArchiveReason.Completed ||
                (record.phase is ProcessPhase.Ended && record.unresolvedWrites().isEmpty()),
        ) {
            "Process is not ready for completed archival"
        }
        val encoded = Json.encodeToString(record)
        require(encoded.encodeToByteArray().size <= ProcessLimits.RECORD_BYTES) { "Process record is too large" }
        val identity = archiveIdentity(ownerHash, encoded, reason)
        val ref = ProcessArchiveRef(identity)
        val key = archiveKey(ownerHash, ref)
        val existing = readStored(key)
        if (existing != null) {
            check(
                existing.ownerHash == ownerHash && existing.recordJson == encoded &&
                    existing.reason == reason,
            ) { "Archive identity conflict" }
            return@withLock existing.snapshot()
        }
        check(
            privateAccess { secrets.keys() }.count { it.value.startsWith(PREFIX) } < CAPACITY,
        ) { "Process archive capacity reached" }
        val stored = StoredArchive(1, ownerHash, ref, reason, clock.now(), encoded)
        privateAccess {
            val chars = Json.encodeToString(stored).toCharArray()
            try {
                Secret(chars).use { secrets.write(key, it) }
            } finally {
                chars.fill('\u0000')
            }
        }
        check(readStored(key) == stored) { "Process archive was not confirmed" }
        log.i { "Protected process snapshot archived" }
        stored.snapshot()
    }

    override suspend fun get(ref: ProcessArchiveRef): ArchivedProcess? = mutex.withLock {
        val keys = privateAccess { secrets.keys() }.filter {
            it.value.startsWith(
                PREFIX,
            ) && it.value.endsWith(ref.value)
        }
        check(keys.size <= 1) { "Ambiguous process archive reference" }
        keys.singleOrNull()?.let { readStored(it)?.snapshot() }
    }

    override suspend fun getOwned(owner: HarnessId, ref: ProcessArchiveRef): ArchivedProcess? = mutex.withLock {
        val hash = owner.value.encodeUtf8().sha256().hex()
        readStored(archiveKey(hash, ref))?.takeIf { it.ownerHash == hash }?.snapshot()
    }

    override suspend fun list(after: ProcessArchiveRef?, limit: Int, process: ProcessId?): ProcessArchivePage =
        listHashed(null, after, limit, process)

    override suspend fun listOwned(
        owner: HarnessId,
        after: ProcessArchiveRef?,
        limit: Int,
        process: ProcessId?,
    ): ProcessArchivePage = listHashed(owner.value.encodeUtf8().sha256().hex(), after, limit, process)

    private suspend fun listHashed(
        ownerHash: String?,
        after: ProcessArchiveRef?,
        limit: Int,
        process: ProcessId?,
    ): ProcessArchivePage = mutex.withLock {
        require(limit in 1..PAGE_SIZE) { "Invalid archive page size" }
        val prefix = ownerHash?.let { PREFIX + it.take(OWNER_PREFIX) + "_" } ?: PREFIX
        val candidates = privateAccess { secrets.keys() }
            .asSequence()
            .filter { it.value.startsWith(prefix) }
            .sortedBy { it.value.takeLast(DIGEST_CHARS) }
            .filter { after == null || it.value.takeLast(DIGEST_CHARS) > after.value }
        val matches = mutableListOf<ArchivedProcess>()
        for (key in candidates) {
            val stored = readStored(key) ?: error("Listed process archive disappeared")
            check(ownerHash == null || stored.ownerHash == ownerHash) { "Archive owner conflict" }
            if (process == null || stored.snapshot().record.id == process) matches += stored.snapshot()
            if (matches.size > limit) break
        }
        val page = matches.take(limit)
        ProcessArchivePage(page, if (matches.size > limit) page.last().ref else null)
    }

    private suspend fun readStored(key: SecretKey): StoredArchive? = privateAccess {
        secrets.read(key)?.use { secret ->
            secret.reveal { chars ->
                Json.decodeFromString<StoredArchive>(chars.concatToString()).also {
                    check(it.v == 1 && isDigest(it.ownerHash)) { "Unsupported archive envelope" }
                    check(archiveKey(it.ownerHash, it.ref) == key) { "Archive identity mismatch" }
                    check(archiveIdentity(it.ownerHash, it.recordJson, it.reason) == it.ref.value) {
                        "Archive content mismatch"
                    }
                }
            }
        }
    }

    private suspend fun <T> privateAccess(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = IllegalStateException("Protected process archive is unavailable")
        log.w(failure) { "Process archive operation failed (${error::class.simpleName.orEmpty()})" }
        throw failure
    }

    @Serializable
    private data class StoredArchive(
        val v: Int,
        val ownerHash: String,
        val ref: ProcessArchiveRef,
        val reason: ProcessArchiveReason,
        val archivedAt: kotlin.time.Instant,
        val recordJson: String,
    ) {
        @Transient
        private val record: ProcessRecord = Json.decodeFromString(recordJson)

        fun snapshot(): ArchivedProcess = ArchivedProcess(ref, record, reason, archivedAt)

        override fun toString(): String = "StoredArchive(***)"
    }

    internal companion object {
        const val CAPACITY = 1024
        private const val PREFIX = "harness_pa_"
        private const val OWNER_PREFIX = 51
        private const val DIGEST_CHARS = 64
        private const val PAGE_SIZE = 100

        private fun isDigest(value: String): Boolean = value.length == DIGEST_CHARS &&
            value.all { it in '0'..'9' || it in 'a'..'f' }

        private fun archiveIdentity(ownerHash: String, recordJson: String, reason: ProcessArchiveReason): String =
            (ownerHash + "\n" + recordJson + "\n" + Json.encodeToString(reason)).encodeUtf8().sha256().hex()

        private fun archiveKey(ownerHash: String, ref: ProcessArchiveRef): SecretKey =
            SecretKey(PREFIX + ownerHash.take(OWNER_PREFIX) + "_" + ref.value)
    }
}
