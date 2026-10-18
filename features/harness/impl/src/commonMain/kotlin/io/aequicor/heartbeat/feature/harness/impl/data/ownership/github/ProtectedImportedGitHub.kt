package io.aequicor.heartbeat.feature.harness.impl.data.ownership.github

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.harness.api.process.PORTABLE_GITHUB_OPERATIONS
import io.aequicor.heartbeat.feature.harness.api.process.PortableEvidence
import io.aequicor.heartbeat.feature.harness.api.process.PortableGitHubTombstone
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Profile-owned immutable import inventory outside ScriptData. Entries and evidence are never evicted: forgetting
 * an old key would authorize a duplicate external action. Quota, corrupted storage or a conflicting import fails
 * closed. The transfer adapter must install this inventory before publishing any imported ProcessRecord.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class ProtectedImportedGitHub(private val secrets: SecretStore) {
    private val mutex = Mutex()
    private val log = Log.tag("ImportedGitHub")

    suspend fun snapshot(local: OwnershipLocalProcess): ImportedGitHubSnapshot = guarded {
        mutex.withLock { load().entries.singleOrNull { it.local == local } ?: ImportedGitHubSnapshot(local) }
    }

    suspend fun lookup(local: OwnershipLocalProcess, key: WriteKey): PortableGitHubTombstone? =
        snapshot(local).tombstones.singleOrNull { it.key == key }

    /** Verified manifest only; this method neither approves its logic nor grants ownership. */
    suspend fun install(local: OwnershipLocalProcess, checkpoint: PortableProcessCheckpoint): Boolean = guarded {
        if (local.process != checkpoint.process || local.incarnation != checkpoint.incarnation) return@guarded false
        mutex.withLock {
            val stored = load()
            val before = stored.entries.singleOrNull { it.local == local } ?: ImportedGitHubSnapshot(local)
            val evidence = checkpoint.history.mapNotNull { it.result } +
                checkpoint.writes.flatMap { listOfNotNull(it.target, it.result) } +
                checkpoint.github.mapNotNull { it.result }
            val next = before.merge(checkpoint.identity.canonical, checkpoint.github, evidence)
                ?: return@withLock false
            if (next == before) return@withLock true
            val entries = stored.entries.filterNot { it.local == local } + next
            val updated = ImportedGitHubEnvelope(entries = entries)
            persist(updated)
            true
        }
    }

    private suspend fun load(): ImportedGitHubEnvelope = secrets.read(KEY)?.use { secret ->
        secret.reveal {
            check(it.size <= MAX_STORE_CHARS) { "Imported GitHub inventory exceeds limit" }
            Json.decodeFromString<ImportedGitHubEnvelope>(it.concatToString())
        }
    } ?: ImportedGitHubEnvelope()

    private suspend fun persist(envelope: ImportedGitHubEnvelope) {
        val chars = Json.encodeToString(envelope).toCharArray()
        check(chars.size <= MAX_STORE_CHARS) { "Imported GitHub inventory exceeds limit" }
        try {
            withContext(NonCancellable) { Secret(chars).use { secrets.write(KEY, it) } }
        } finally {
            chars.fill('\u0000')
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = IllegalStateException("Imported GitHub inventory is unavailable")
        log.w(failure) { "imported GitHub inventory rejected (${error::class.simpleName.orEmpty()})" }
        throw failure
    }
}

/** Immutable protected lifetime inventory. A missing imported inventory does not hide the native GitHub registry. */
@Serializable
internal data class ImportedGitHubSnapshot(
    val local: OwnershipLocalProcess,
    @Required val identity: String? = null,
    @Required val tombstones: List<PortableGitHubTombstone> = emptyList(),
    @Required val evidence: List<PortableEvidence> = emptyList(),
) {
    init {
        require(tombstones.size <= PORTABLE_GITHUB_OPERATIONS && evidence.size <= MAX_LIFETIME_EVIDENCE)
        require(tombstones.map { it.key }.distinct().size == tombstones.size)
        require(evidence.map { it.digest }.distinct().size == evidence.size)
        require(tombstones.mapNotNull { it.result }.all { it in evidence })
        require(identity != null || (tombstones.isEmpty() && evidence.isEmpty()))
    }

    fun merge(
        identity: String,
        tombstones: List<PortableGitHubTombstone>,
        evidence: List<PortableEvidence>,
    ): ImportedGitHubSnapshot? {
        if (this.identity != null && this.identity != identity) return null
        val keys = (this.tombstones + tombstones).groupBy { it.key }
        val seals = (this.evidence + evidence).groupBy { it.digest }
        return if (keys.values.any { it.distinct().size != 1 } || seals.values.any { it.distinct().size != 1 }) {
            null
        } else {
            copy(
                identity = identity,
                tombstones = keys.values.map { it.first() },
                evidence = seals.values.map { it.first() },
            )
        }
    }

    override fun toString(): String = "ImportedGitHubSnapshot(***)"
}

@Serializable
private data class ImportedGitHubEnvelope(
    @Required val version: Int = 1,
    @Required val entries: List<ImportedGitHubSnapshot> = emptyList(),
) {
    init {
        require(version == 1 && entries.size <= MAX_LIFETIMES)
        require(entries.map { it.local }.distinct().size == entries.size)
    }

    override fun toString(): String = "ImportedGitHubEnvelope(***)"
}

private val KEY = SecretKey("harness_imported_github_v1")
private const val MAX_STORE_CHARS = 4 * 1024 * 1024
private const val MAX_LIFETIMES = 64
private const val MAX_LIFETIME_EVIDENCE = 256
