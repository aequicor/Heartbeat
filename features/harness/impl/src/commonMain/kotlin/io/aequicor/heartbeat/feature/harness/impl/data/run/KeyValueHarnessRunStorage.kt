package io.aequicor.heartbeat.feature.harness.impl.data.run

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
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessKeyValueJournal
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessRawValue
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageConflict
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageException
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock

/** Entries and their permanent discovery index share the feature-private durable commit point. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class KeyValueHarnessRunStorage(private val store: KeyValueStore, private val clock: Clock) :
    HarnessRunStorage {
    @Inject
    constructor(
        @ForScope(ProfileScope::class) stores: DataStores,
        clock: Clock,
    ) : this(stores.keyValue(SPEC), clock)

    private val lock = Mutex()
    private val log = Log.tag("HarnessRunStorage")
    private val journal = HarnessKeyValueJournal(store, clock)

    @HighFrequency
    override suspend fun load(): List<WorkflowRun> = access {
        log.v { "load harness storage" }
        journal.recover()
        val records = read()
        val retained = retainedRuns(records.runs, clock.now())
        write(records, retained)
        retained
    }

    @HighFrequency
    override suspend fun save(run: WorkflowRun, expectedGeneration: Long?): Boolean = access {
        log.v { "save harness storage" }
        journal.recover()
        val records = read()
        val durable = run.copy(awaiting = emptyMap())
        val existing = records.runs.singleOrNull { it.id == durable.id }
        val isMatching = when {
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
        if (isMatching) {
            write(
                records,
                retainedRuns(records.runs.filterNot { it.id == durable.id } + durable, clock.now()),
            )
        }
        isMatching
    }

    private suspend fun read(): HarnessRunRecords {
        val text = store.get(INDEX)
        val index = text?.let { decodeRunRecord<HarnessRunIndex>(it) } ?: HarnessRunIndex()
        val values = mutableMapOf<RunId, String?>()
        val runs = mutableListOf<WorkflowRun>()
        for (header in index.entries) {
            val raw = store.get(runKey(header.id))
            values[header.id] = raw
            if (raw == null) {
                if (header.expiresAt?.let { it <= clock.now() } != true) throw HarnessStorageCorrupt()
            } else {
                val run = decodeRunRecord<WorkflowRun>(raw)
                if (run.header() != header) throw HarnessStorageCorrupt()
                runs += run
            }
        }
        return HarnessRunRecords(text, index, values, runs)
    }

    private suspend fun write(previous: HarnessRunRecords, runs: List<WorkflowRun>) {
        val before = mutableMapOf<String, HarnessRawValue?>()
        val after = mutableMapOf<String, HarnessRawValue?>()
        val nextIndex = HarnessRunIndex(runs.map { it.header() })
        val indexText = Json.encodeToString(nextIndex)
        if (previous.index != nextIndex) {
            before[INDEX.name] = previous.indexText?.let { HarnessRawValue(it) }
            after[INDEX.name] = HarnessRawValue(indexText)
        }
        val previousHeaders = previous.index.entries.associateBy { it.id }
        val next = runs.associateBy { it.id }
        for (id in previousHeaders.keys + next.keys) {
            val old = previous.values[id]?.let { HarnessRawValue(it, previousHeaders[id]?.expiresAt) }
            val new = next[id]?.let { HarnessRawValue(Json.encodeToString(it), it.header().expiresAt) }
            if (old != new) {
                before[runKey(id).name] = old
                after[runKey(id).name] = new
            }
        }
        if (before.isNotEmpty()) {
            val operation = Json.encodeToString(after).encodeUtf8().sha256().hex()
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
