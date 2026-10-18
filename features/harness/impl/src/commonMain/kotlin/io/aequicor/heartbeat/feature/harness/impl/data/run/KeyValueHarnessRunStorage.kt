package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.withoutLiveRequests
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessKeyValueJournal
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessRawValue
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageConflict
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageException
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock

/**
 * Entries and their permanent discovery index share the feature-private durable commit point. A save reads and
 * writes only the index and its target run (plus values that retention drops); JSON coding runs on [codec].
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class KeyValueHarnessRunStorage(
    private val store: KeyValueStore,
    private val clock: Clock,
    private val codec: CoroutineDispatcher,
) : HarnessRunStorage {
    @Inject
    constructor(
        @ForScope(ProfileScope::class) stores: DataStores,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ) : this(stores.keyValue(SPEC), clock, dispatchers.default)

    private val lock = Mutex()
    private val log = Log.tag("HarnessRunStorage")
    private val journal = HarnessKeyValueJournal(store, clock)

    @HighFrequency
    override suspend fun load(): List<WorkflowRun> = access {
        log.v { "load harness storage" }
        journal.recover()
        val index = readIndex()
        val raw = mutableMapOf<RunId, String>()
        val runs = mutableListOf<WorkflowRun>()
        for (header in index.value.entries) {
            val text = readRun(header) ?: continue
            raw[header.id] = text
            runs += decodeRun(text, header)
        }
        val retained = retainedHeaders(index.value.entries.filter { it.id in raw }, clock.now())
        commit(index, retained, raw, emptyMap())
        val kept = retained.mapTo(mutableSetOf()) { it.id }
        runs.filter { it.id in kept }.sortedBy { it.id.value }
    }

    @HighFrequency
    override suspend fun find(id: RunId): WorkflowRun? = access {
        log.v { "find harness run" }
        journal.recover()
        val header = readIndex().value.entries.singleOrNull { it.id == id } ?: return@access null
        readRun(header)?.let { decodeRun(it, header) }
    }

    @HighFrequency
    override suspend fun save(run: WorkflowRun, expectedGeneration: Long?): Boolean = access {
        log.v { "save harness storage" }
        journal.recover()
        val index = readIndex()
        val durable = run.withoutLiveRequests()
        val header = index.value.entries.singleOrNull { it.id == durable.id }
        val existingText = header?.let { readRun(it) }
        val existing = if (header != null && existingText != null) decodeRun(existingText, header) else null
        val isMatching = isAccepted(existing, durable, expectedGeneration)
        if (isMatching && existing != durable) {
            val others = index.value.entries.filterNot { it.id == durable.id }
            val retained = retainedHeaders(others + durable.header(), clock.now())
            val raw = readDropped(index, retained) + listOfNotNull(existingText?.let { durable.id to it })
            commit(index, retained, raw, mapOf(durable.id to encode(durable)))
        }
        isMatching
    }

    private fun isAccepted(existing: WorkflowRun?, durable: WorkflowRun, expectedGeneration: Long?): Boolean = when {
        existing == durable -> true

        existing == null -> expectedGeneration == null

        expectedGeneration == null || existing.driverGeneration != expectedGeneration -> false

        existing.status != WorkflowStatus.Running -> false

        else -> {
            if (!existing.hasSameIdentity(durable) || durable.driverGeneration < expectedGeneration ||
                durable.driverGeneration - expectedGeneration > 1
            ) {
                throw HarnessStorageConflict()
            }
            durable.isMonotonicUpdateOf(existing)
        }
    }

    override suspend fun discard(run: WorkflowRun): Boolean = access {
        log.v { "discard unstarted harness run" }
        journal.recover()
        val index = readIndex()
        val header = index.value.entries.singleOrNull { it.id == run.id } ?: return@access true
        val text = readRun(header) ?: return@access false
        if (decodeRun(text, header) != run.withoutLiveRequests()) return@access false
        commit(index, index.value.entries.filterNot { it.id == run.id }, mapOf(run.id to text), emptyMap())
        true
    }

    override suspend fun isPublished(id: RunId): Boolean = access {
        log.v { "read harness run outbox mark" }
        journal.recover()
        readIndex().value.entries.singleOrNull { it.id == id }?.isPublished == true
    }

    override suspend fun markPublished(id: RunId) = access {
        log.v { "mark harness run published" }
        journal.recover()
        val index = readIndex()
        val header = index.value.entries.singleOrNull { it.id == id }
        if (header != null && header.finishedAt != null && !header.isPublished) {
            val next = index.value.entries.map { if (it.id == id) it.copy(isPublished = true) else it }
            commit(index, next, emptyMap(), emptyMap())
        }
    }

    private suspend fun readIndex(): StoredRunIndex {
        val text = store.get(INDEX)
        val value = text?.let { withContext(codec) { decodeRunRecord<HarnessRunIndex>(it) } } ?: HarnessRunIndex()
        return StoredRunIndex(text, value)
    }

    /** Null only for a committed terminal value whose retention already expired; anything else fails closed. */
    private suspend fun readRun(header: HarnessRunHeader): String? {
        val text = store.get(runKey(header.id))
        val expiresAt = header.expiresAt
        if (text == null && (expiresAt == null || expiresAt > clock.now())) throw HarnessStorageCorrupt()
        return text
    }

    private suspend fun decodeRun(text: String, header: HarnessRunHeader): WorkflowRun {
        val run = withContext(codec) { decodeRunRecord<WorkflowRun>(text) }
        if (run.header() != header.identity()) throw HarnessStorageCorrupt()
        return run
    }

    private suspend fun encode(run: WorkflowRun): String = withContext(codec) { Json.encodeToString(run) }

    /** Before-images of values that retention removes; an already expired value needs no deletion. */
    private suspend fun readDropped(index: StoredRunIndex, retained: List<HarnessRunHeader>): Map<RunId, String> {
        val kept = retained.mapTo(mutableSetOf()) { it.id }
        return index.value.entries.filter { it.id !in kept }.mapNotNull { header ->
            store.get(runKey(header.id))?.let { header.id to it }
        }.toMap()
    }

    /**
     * Writes the next index and only changed values: [updates] for kept runs, deletions for runs absent from
     * [next]. [raw] holds the committed text of every updated or deleted run that still had a value.
     */
    private suspend fun commit(
        index: StoredRunIndex,
        next: List<HarnessRunHeader>,
        raw: Map<RunId, String>,
        updates: Map<RunId, String>,
    ) {
        val before = mutableMapOf<String, HarnessRawValue?>()
        val after = mutableMapOf<String, HarnessRawValue?>()
        val nextIndex = HarnessRunIndex(next)
        if (index.value != nextIndex) {
            before[INDEX.name] = index.text?.let { HarnessRawValue(it) }
            after[INDEX.name] = HarnessRawValue(withContext(codec) { Json.encodeToString(nextIndex) })
        }
        val previous = index.value.entries.associateBy { it.id }
        val kept = next.associateBy { it.id }
        for ((id, header) in previous) {
            val old = raw[id]
            if (id !in kept && old != null) {
                before[runKey(id).name] = HarnessRawValue(old, header.expiresAt)
                after[runKey(id).name] = null
            }
        }
        for ((id, text) in updates) {
            val header = kept[id] ?: continue
            before[runKey(id).name] = raw[id]?.let { HarnessRawValue(it, previous[id]?.expiresAt) }
            after[runKey(id).name] = HarnessRawValue(text, header.expiresAt)
        }
        if (before.isNotEmpty()) {
            val operation = withContext(codec) { Json.encodeToString(after).encodeUtf8().sha256().hex() }
            journal.commit(operation, before, after)
        }
    }

    private fun runKey(id: RunId) = stringKey("run.${id.value}")

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
        val SPEC = KeyValueSpec("harness_runs")
        val INDEX = stringKey("index")
    }
}

/** Ephemeral coherent index read; private journal content must not reach incidental logs. */
private data class StoredRunIndex(val text: String?, val value: HarnessRunIndex) {
    override fun toString(): String = "StoredRunIndex(***)"
}
