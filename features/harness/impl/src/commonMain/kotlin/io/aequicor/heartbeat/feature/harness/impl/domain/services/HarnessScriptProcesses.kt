package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessLimits
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRejection
import io.aequicor.heartbeat.feature.harness.api.process.reduce
import io.aequicor.heartbeat.feature.harness.api.script.ScriptData
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcesses
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

/**
 * Durable processes over the harness's [ScriptData]: one versioned envelope per process under `process_<id>` and an
 * ordered index of ids under [INDEX_KEY]. [ScriptData] writes one key at a time, so an open writes the index first
 * and the record second, and a removal deletes the record first and the index entry second. The index is therefore
 * always a superset of the records: a crash between the two writes leaves an id without a record, which reads as
 * absent, never a record that listing cannot find.
 *
 * Every mutation is a [ScriptData.update]: the pure reduction runs against the freshly read record and is retried
 * on a concurrent write. Nothing that was stored is ever logged; unreadable content surfaces as a generic failure.
 */
internal class HarnessScriptProcesses(private val data: ScriptData, private val clock: Clock) : ScriptProcesses {
    private val log = Log.tag("HarnessProcesses")

    override suspend fun get(id: ProcessId): ProcessRecord? = data.read(id.storageKey).value?.let(::decodeRecord)

    override suspend fun list(): List<ProcessRecord> = readIndex(data.read(INDEX_KEY).value)
        .mapNotNull { get(it) }

    override suspend fun open(id: ProcessId, inputRevision: Long): ProcessRecord {
        require(inputRevision >= 0) { "Invalid input revision" }
        addToIndex(id)
        var opened: ProcessRecord? = null
        data.update(id.storageKey) { current ->
            val existing = current?.let(::decodeRecord)
            val record = existing ?: ProcessRecord(id, clock.now(), inputRevision = inputRevision)
            opened = record
            if (existing == null) encodeRecord(record) else current
        }
        log.i { "process opened" }
        return checkNotNull(opened) { "Process was not opened" }
    }

    override suspend fun apply(id: ProcessId, command: ProcessCommand): ProcessReduction {
        var outcome: ProcessReduction = ProcessReduction.Rejected(ProcessRejection.UnknownProcess)
        data.update(id.storageKey) { current ->
            val record = current?.let(::decodeRecord)
            if (record == null) {
                outcome = ProcessReduction.Rejected(ProcessRejection.UnknownProcess)
                current
            } else {
                val reduction = record.reduce(command, clock.now())
                outcome = reduction
                if (reduction is ProcessReduction.Changed) encodeRecord(reduction.record) else current
            }
        }
        val result = outcome
        val summary = "process command ${command::class.simpleName.orEmpty()} -> ${result::class.simpleName.orEmpty()}"
        if (result is ProcessReduction.Changed) log.i { summary } else log.v { summary }
        return result
    }

    override suspend fun remove(id: ProcessId): Boolean {
        var isRemoved = false
        data.update(id.storageKey) { current ->
            val record = current?.let(::decodeRecord)
            isRemoved = record == null || record.isRemovable()
            if (isRemoved) null else current
        }
        if (isRemoved) dropFromIndex(id)
        log.i { "process removal ${if (isRemoved) "done" else "refused"}" }
        return isRemoved
    }

    private suspend fun addToIndex(id: ProcessId) {
        data.update(INDEX_KEY) { current ->
            val ids = readIndex(current)
            when {
                id in ids -> current

                else -> {
                    require(ids.size < ProcessLimits.PROCESSES) { "Too many processes" }
                    encodeIndex(ids + id)
                }
            }
        }
    }

    private suspend fun dropFromIndex(id: ProcessId) {
        data.update(INDEX_KEY) { current ->
            val ids = readIndex(current)
            when {
                id !in ids -> current
                ids.size == 1 -> null
                else -> encodeIndex(ids - id)
            }
        }
    }

    private fun ProcessRecord.isRemovable(): Boolean = phase is ProcessPhase.Ended && unresolvedWrites().isEmpty()

    private fun decodeRecord(element: JsonElement): ProcessRecord = guarded {
        val stored = Json.decodeFromJsonElement(StoredProcess.serializer(), element)
        check(stored.v == VERSION) { "Unsupported process version" }
        stored.record
    }

    private fun encodeRecord(record: ProcessRecord): JsonElement {
        val element = Json.encodeToJsonElement(StoredProcess.serializer(), StoredProcess(VERSION, record))
        require(element.toString().encodeToByteArray().size <= ProcessLimits.RECORD_BYTES + ENVELOPE_BYTES) {
            "Process record is too large"
        }
        return element
    }

    private fun readIndex(element: JsonElement?): List<ProcessId> = if (element == null) {
        emptyList()
    } else {
        guarded {
            val stored = Json.decodeFromJsonElement(StoredIndex.serializer(), element)
            check(stored.v == VERSION) { "Unsupported process index version" }
            stored.ids
        }
    }

    private fun encodeIndex(ids: List<ProcessId>): JsonElement =
        Json.encodeToJsonElement(StoredIndex.serializer(), StoredIndex(VERSION, ids))

    /** Decoder messages may quote stored values: neither they nor their cause may escape or reach the log. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = IllegalStateException("Stored process is unreadable")
        log.w(failure) { "Stored process could not be read (${error::class.simpleName.orEmpty()})" }
        throw failure
    }

    @Serializable
    private data class StoredProcess(val v: Int, val record: ProcessRecord) {
        override fun toString(): String = "StoredProcess(***)"
    }

    @Serializable
    private data class StoredIndex(val v: Int, val ids: List<ProcessId>) {
        override fun toString(): String = "StoredIndex(***)"
    }

    private companion object {
        /** Not `process_<something>`: no process id can collide with it. */
        const val INDEX_KEY = "processes"
        const val VERSION = 1
        const val ENVELOPE_BYTES = 64
    }
}
