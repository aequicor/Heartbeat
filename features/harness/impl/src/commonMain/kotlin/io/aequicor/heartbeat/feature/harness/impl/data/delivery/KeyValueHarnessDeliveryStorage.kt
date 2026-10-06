package io.aequicor.heartbeat.feature.harness.impl.data.delivery

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessKeyValueJournal
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessRawValue
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessDeliveryMarker
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessDeliverySnapshot
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessDeliveryStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageConflict
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageException
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A single expiring receipt set uses the same private journal and absolute deadlines as the library. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class KeyValueHarnessDeliveryStorage(private val store: KeyValueStore, private val clock: Clock) :
    HarnessDeliveryStorage {
    @Inject
    constructor(
        @ForScope(ProfileScope::class) stores: DataStores,
        clock: Clock,
    ) : this(stores.keyValue(SPEC), clock)

    private val lock = Mutex()
    private val log = Log.tag("HarnessDeliveryStorage")
    private val journal = HarnessKeyValueJournal(store, clock)

    @HighFrequency
    override suspend fun snapshot(session: SessionRef): HarnessDeliverySnapshot = access {
        log.v { "snapshot harness storage" }
        journal.recover()
        val old = read()
        val entries = retained(old)
        val current = entries.singleOrNull { it.session == session }
            ?: HarnessDeliveryRecord(session, newGeneration(), clock.now())
        val next = if (entries.any { it.session == session }) {
            entries
        } else {
            (entries + current).takeLast(
                DELIVERY_SESSIONS,
            )
        }
        write(old, next)
        current.snapshot()
    }

    @HighFrequency
    override suspend fun accepted(
        session: SessionRef,
        expectedGeneration: String,
        activeSetSha: String,
        markers: List<HarnessDeliveryMarker>,
        coveredDisabled: Set<HarnessName>,
    ): Boolean = access {
        log.v { "accepted harness storage" }
        val hasUniqueMarkers = markers.map { it.harness }.distinct().size == markers.size &&
            markers.map { it.name }.distinct().size == markers.size
        if (!isDeliveryDigest(activeSetSha) || markers.size > HarnessLimits.ACTIVE_PER_SESSION || !hasUniqueMarkers) {
            throw HarnessStorageConflict()
        }
        journal.recover()
        val old = read()
        val entries = retained(old)
        val current = entries.singleOrNull { it.session == session }
        val isMatching = current != null && current.generation == expectedGeneration &&
            coveredDisabled.containsAll(current.pendingDisabled)
        if (isMatching) {
            val replacement = current.copy(
                generation = newGeneration(),
                updatedAt = clock.now(),
                activeSetSha = activeSetSha,
                markers = markers,
                pendingDisabled = emptySet(),
            )
            write(old, entries.filterNot { it.session == session } + replacement)
        } else {
            write(old, entries)
        }
        isMatching
    }

    @HighFrequency
    override suspend fun reset(session: SessionRef) = access {
        log.v { "reset harness storage" }
        journal.recover()
        val old = read()
        write(
            old,
            retained(old).map { entry ->
                if (entry.session != session) entry else entry.copy(generation = newGeneration(), activeSetSha = null)
            },
        )
    }

    @HighFrequency
    override suspend fun removeHarness(harness: HarnessId) = access {
        log.v { "removeHarness harness storage" }
        journal.recover()
        val old = read()
        write(
            old,
            retained(old).map { entry ->
                val marker = entry.markers.singleOrNull { it.harness == harness }
                if (marker == null) {
                    entry
                } else {
                    entry.copy(
                        generation = newGeneration(),
                        activeSetSha = null,
                        markers = entry.markers.filterNot { it.harness == harness },
                        pendingDisabled = entry.pendingDisabled + marker.name,
                    )
                }
            },
        )
    }

    private suspend fun read(): ReadDeliveryRecords {
        val text = store.get(DELIVERIES)
        return ReadDeliveryRecords(text, text?.let(::decodeDeliveryRecords) ?: HarnessDeliveryRecords())
    }

    private fun retained(records: ReadDeliveryRecords): List<HarnessDeliveryRecord> =
        records.value.entries.filter { it.expiresAt > clock.now() }.sortedBy { it.updatedAt }

    private suspend fun write(old: ReadDeliveryRecords, entries: List<HarnessDeliveryRecord>) {
        val next = HarnessDeliveryRecords(entries)
        if (old.value == next) return
        val before = old.text?.let { HarnessRawValue(it, old.value.entries.maxOfOrNull { entry -> entry.expiresAt }) }
        val after = raw(next)
        val operation = Json.encodeToString(next).encodeUtf8().sha256().hex()
        journal.commit(operation, mapOf(DELIVERIES.name to before), mapOf(DELIVERIES.name to after))
    }

    private fun raw(records: HarnessDeliveryRecords): HarnessRawValue? = records.entries.takeIf {
        it.isNotEmpty()
    }?.let {
        HarnessRawValue(Json.encodeToString(records), it.maxOf { entry -> entry.expiresAt })
    }

    private fun newGeneration(): String = Uuid.random().toString()

    private suspend fun <T> access(block: suspend () -> T): T = lock.withLock {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: HarnessStorageException) {
            throw error
        } catch (error: Exception) {
            log.w(HarnessStorageUncertain()) { "Harness storage unavailable (${error::class.simpleName.orEmpty()})" }
            throw HarnessStorageUncertain()
        }
    }

    internal companion object {
        val SPEC = KeyValueSpec("harness_deliveries")
        val DELIVERIES = stringKey("deliveries")
    }
}

private data class ReadDeliveryRecords(val text: String?, val value: HarnessDeliveryRecords) {
    override fun toString(): String = "ReadDeliveryRecords(***)"
}
