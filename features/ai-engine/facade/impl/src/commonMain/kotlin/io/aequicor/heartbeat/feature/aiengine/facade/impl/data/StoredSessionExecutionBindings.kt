package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionInstance
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionExecutionBindings
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionExecutionOpening
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Authority survives metadata cleanup and profile restart; no TTL and no recovery-to-empty on corrupt storage. */
internal class StoredSessionExecutionBindings(private val store: KeyValueStore) : SessionExecutionBindings {
    private val log = Log.tag("SessionExecutionBindings")
    private val mutex = Mutex()

    init {
        require(store.spec.readPolicy == KeyValueReadPolicy.Strict) { "Execution bindings require strict storage" }
    }

    override suspend fun find(ref: SessionRef): SessionExecutionBinding? = mutex.withLock {
        log.v { "read native execution binding" }
        read().entries.singleOrNull { it.ref == ref }?.binding
    }

    override suspend fun bind(ref: SessionRef, binding: SessionExecutionBinding): Unit = mutex.withLock {
        val records = read()
        val known = records.entries.singleOrNull { it.ref == ref }
        check(known == null || known.binding == binding) { "Native execution authority cannot change" }
        if (known == null) {
            store.set(RecordsKey, records.copy(entries = records.entries + ExecutionBindingEntry(ref, binding)))
            log.d { "native execution authority recorded" }
        }
    }

    override suspend fun reserve(opening: SessionExecutionOpening): Unit = mutex.withLock {
        val records = read()
        val known = records.openings.singleOrNull { it.token == opening.token }
        check(known == null || known == opening) { "Native opening reservation cannot change" }
        if (known == null) store.set(RecordsKey, records.copy(openings = records.openings + opening))
        log.d { "executor opening reserved before native creation" }
    }

    override suspend fun complete(opening: SessionExecutionOpening, ref: SessionRef): Unit = mutex.withLock {
        val records = read()
        check(ref.engine == opening.engine) { "Opening returned another engine" }
        val known = records.entries.singleOrNull { it.ref == ref }
        check(known == null || known.binding == opening.binding) { "Native execution authority cannot change" }
        val pending = records.openings.singleOrNull { it.token == opening.token }
        check(pending == opening || (pending == null && known?.binding == opening.binding)) {
            "Native opening reservation is unavailable"
        }
        val appended = ExecutionBindingEntry(ref, opening.binding)
        val entries = if (known == null) records.entries + appended else records.entries
        store.set(RecordsKey, records.copy(entries = entries, openings = records.openings.filter { it != opening }))
        log.d { "executor opening durably bound" }
    }

    override suspend fun pending(): List<SessionExecutionOpening> = mutex.withLock { read().openings }

    override suspend fun references(instance: SessionExecutionInstance): Set<SessionRef> = mutex.withLock {
        log.v { "read native segments of execution instance" }
        read().entries.filter { it.binding.instance == instance }.mapTo(mutableSetOf()) { it.ref }
    }

    private suspend fun read(): ExecutionBindingRecords = (store.get(RecordsKey) ?: ExecutionBindingRecords()).also {
        check(it.openings.map { opening -> opening.token }.distinct().size == it.openings.size) {
            "Ambiguous native opening reservations"
        }
        check(it.version == 1) { "Unsupported execution binding version" }
        check(it.entries.map { entry -> entry.ref }.distinct().size == it.entries.size) {
            "Ambiguous native execution bindings"
        }
    }
}

internal val SessionExecutionBindingSpec = KeyValueSpec(
    "engine_execution_bindings",
    readPolicy = KeyValueReadPolicy.Strict,
)
private val RecordsKey = jsonKey("bindings", ExecutionBindingRecords.serializer())

@Serializable
private data class ExecutionBindingRecords(
    val version: Int = 1,
    val entries: List<ExecutionBindingEntry> = emptyList(),
    val openings: List<SessionExecutionOpening> = emptyList(),
)

@Serializable
private data class ExecutionBindingEntry(val ref: SessionRef, val binding: SessionExecutionBinding)
