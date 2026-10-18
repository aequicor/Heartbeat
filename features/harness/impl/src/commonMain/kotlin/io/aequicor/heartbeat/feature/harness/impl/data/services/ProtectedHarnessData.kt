package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.script.ScriptDataSnapshot
import io.aequicor.heartbeat.feature.harness.api.script.ScriptDataWriteResult
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.HarnessOwnershipRuntime
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDataStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessProcessArchiveStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okio.ByteString.Companion.encodeUtf8

/**
 * One encrypted envelope per harness, serialized by a profile singleton. SecretStore atomically replaces the
 * envelope; values never pass through ordinary KV or logs. The revision belongs to the namespace, so deleting
 * keys needs no tombstones. Recovery prunes orphan envelopes after the library journal is recovered.
 */
@SingleIn(ProfileScope::class)
internal class ProtectedHarnessData(
    private val secrets: SecretStore,
    override val archives: HarnessProcessArchiveStorage,
    override val ownership: HarnessOwnershipRuntime? = null,
) : HarnessDataStorage {
    override val processMutex = Mutex()
    private val mutex = Mutex()
    private val revisions = MutableStateFlow<Map<HarnessId, Long>>(emptyMap())
    private val log = Log.tag("HarnessData")

    override fun revision(owner: HarnessId): Flow<Long> = revisions.map { it[owner] ?: 0L }.distinctUntilChanged()

    override suspend fun read(owner: HarnessId, key: String, authorize: () -> Unit): ScriptDataSnapshot {
        validateKey(key)
        return mutex.withLock {
            authorize()
            val current = load(owner)
            authorize()
            current.snapshot(key)
        }
    }

    override suspend fun write(
        owner: HarnessId,
        key: String,
        value: JsonElement?,
        expectedRevision: Long,
        authorize: () -> Unit,
    ): ScriptDataWriteResult {
        validateKey(key)
        require(expectedRevision >= 0) { "Invalid data revision" }
        return mutex.withLock {
            authorize()
            val current = load(owner)
            authorize()
            if (current.revision != expectedRevision) {
                return@withLock ScriptDataWriteResult.Conflict(current.snapshot(key))
            }
            val entries = if (value == null) current.entries - key else current.entries + (key to value)
            if (entries == current.entries) return@withLock ScriptDataWriteResult.Written(current.snapshot(key))
            check(current.revision < Long.MAX_VALUE) { "Data revision exhausted" }
            val next = HarnessDataEnvelope(current.revision + 1, entries)
            val encoded = Json.encodeToString(next)
            require(
                entries.size <= HarnessLimits.DATA_KEYS && encoded.encodeToByteArray().size <= HarnessLimits.DATA_BYTES,
            ) {
                "Harness data quota exceeded"
            }
            authorize()
            // Once admitted, finish the atomic write and publish its revision even if the caller leaves the surface.
            // Revocation prevents later transactions; it cannot undo an already admitted durable commit.
            withContext(NonCancellable) {
                persist(owner, encoded)
                revisions.value = revisions.value + (owner to next.revision)
            }
            log.v { "Harness data committed" }
            ScriptDataWriteResult.Written(next.snapshot(key))
        }
    }

    override suspend fun prepareRemoval(owner: HarnessId) = mutex.withLock {
        archiveNamespace(owner.storageKey())
    }

    override suspend fun retainOwners(owners: Set<HarnessId>) = mutex.withLock {
        val retained = owners.mapTo(mutableSetOf()) { it.storageKey() }
        secrets.keys().filter { it.value.startsWith(DATA_PREFIX) && it !in retained }.forEach { key ->
            archiveNamespace(key)
            check(secrets.remove(key) !is SecretRemoval.InUse) { "Deleted harness data is still referenced" }
        }
        revisions.value = revisions.value.filterKeys { it in owners }
        log.v { "Removed orphan harness data namespaces" }
    }

    private suspend fun archiveNamespace(key: SecretKey) {
        val records = privateAccess {
            HarnessScriptProcesses.decodeStored(
                loadKey(key).entries[HarnessScriptProcesses.STORAGE_KEY],
            )
        }
        if (records.isEmpty() || (!archives.isEnabled() && records.none { it.definition != null })) return
        for (record in records) archives.storeOrphan(key.value.removePrefix(DATA_PREFIX), record)
    }

    private suspend fun load(owner: HarnessId): HarnessDataEnvelope = loadKey(owner.storageKey())

    private suspend fun loadKey(key: SecretKey): HarnessDataEnvelope = privateAccess {
        secrets.read(key)?.use { secret ->
            secret.reveal { chars ->
                Json.decodeFromString<HarnessDataEnvelope>(chars.concatToString()).also {
                    check(it.revision >= 0 && it.entries.size <= HarnessLimits.DATA_KEYS) { "Invalid data envelope" }
                }
            }
        } ?: HarnessDataEnvelope()
    }

    private suspend fun persist(owner: HarnessId, encoded: String) = privateAccess {
        val chars = encoded.toCharArray()
        try {
            Secret(chars).use { secrets.write(owner.storageKey(), it) }
        } finally {
            chars.fill('\u0000')
        }
    }

    private suspend fun <T> privateAccess(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Decoder/backend exceptions may contain private values. Neither the cause nor its message may escape.
        val failure = IllegalStateException("Harness data storage is unavailable")
        log.w(failure) { "Harness data operation failed (${error::class.simpleName.orEmpty()})" }
        throw failure
    }
}

@Serializable
private data class HarnessDataEnvelope(val revision: Long = 0, val entries: Map<String, JsonElement> = emptyMap()) {
    fun snapshot(key: String) = ScriptDataSnapshot(entries[key], revision)
    override fun toString(): String = "HarnessDataEnvelope(***)"
}

private fun validateKey(key: String) {
    require(DATA_KEY.matches(key)) { "Invalid data key" }
}

private fun HarnessId.storageKey() = SecretKey(DATA_PREFIX + value.encodeUtf8().sha256().hex())
private const val DATA_PREFIX = "harness_data_"
private val DATA_KEY = Regex("[a-z][a-z0-9_]{0,63}")
