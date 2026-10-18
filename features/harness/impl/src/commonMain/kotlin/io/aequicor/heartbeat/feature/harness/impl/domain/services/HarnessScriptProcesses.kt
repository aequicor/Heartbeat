package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.process.ArchivedProcess
import io.aequicor.heartbeat.feature.harness.api.process.AttemptFailure
import io.aequicor.heartbeat.feature.harness.api.process.AttemptOutcome
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchivePage
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchiveReason
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchiveRef
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessDefinition
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessLimits
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRejection
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflow
import io.aequicor.heartbeat.feature.harness.api.process.reduce
import io.aequicor.heartbeat.feature.harness.api.script.ScriptData
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcesses
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Durable processes over the harness's [ScriptData]: every process of the harness lives in one versioned envelope
 * under the single reserved key [STORAGE_KEY], in the order the processes were opened. [ScriptData] writes one key
 * at a time with a compare-and-set, so opening, changing and removing a process is one atomic write: there is no
 * index that could disagree with the records, no half-opened process that occupies a slot, and no race between an
 * open and a removal of the same process.
 *
 * Every mutation is a [ScriptData.update]: the pure reduction runs against the freshly read envelope and is retried
 * on a concurrent write. Nothing that was stored is ever logged; unreadable content surfaces as a generic failure
 * and, being one envelope, makes the whole set unreadable until the [STORAGE_KEY] entry is deleted.
 */
internal class HarnessScriptProcesses(
    private val data: ScriptData,
    private val clock: Clock,
    private val gate: Mutex = Mutex(),
    private val owner: HarnessId? = null,
    private val runs: HarnessRunStorage? = null,
    private val definition: ProcessDefinition? = null,
    private val isVersioningEnabled: suspend () -> Boolean = { true },
    private val archives: HarnessProcessArchiveStorage? = null,
) : ScriptProcesses {
    private val log = Log.tag("HarnessScriptProcesses")

    override suspend fun get(id: ProcessId): ProcessRecord? = decode(data.read(STORAGE_KEY).value).firstOrNull {
        it.id == id
    }

    override suspend fun list(): List<ProcessRecord> = decode(data.read(STORAGE_KEY).value)

    override suspend fun open(id: ProcessId, inputRevision: Long): ProcessRecord = gate.withLock {
        require(inputRevision >= 0) { "Invalid input revision" }
        val binding = if (isVersioningEnabled()) definition else null
        if (get(id) == null) makeRoom()
        var opened: ProcessRecord? = null
        var isCreated = false
        data.update(STORAGE_KEY) { current ->
            val records = decode(current)
            val existing = records.firstOrNull { it.id == id }
            isCreated = existing == null
            opened = existing
            if (existing != null) {
                current
            } else {
                require(records.size < ProcessLimits.PROCESSES) { "Too many processes" }
                val record = ProcessRecord(
                    id,
                    clock.now(),
                    inputRevision = inputRevision,
                    incarnation = Uuid.random().toHexString(),
                    definition = binding,
                )
                opened = record
                encode(records + record, record)
            }
        }
        if (isCreated) log.i { "process opened" } else log.v { "process already open" }
        checkNotNull(opened) { "Process was not opened" }
    }

    override suspend fun apply(id: ProcessId, command: ProcessCommand.Record): ProcessReduction {
        val result = gate.withLock { reduceStored(id, command) }
        if (result is ProcessReduction.Changed || command is ProcessCommand.Finish || command is ProcessCommand.Close) {
            releaseSettled()
        }
        return result
    }

    /**
     * Host-only path for workflow reservations and their cancellation. The script API cannot reach it: scripts use
     * [apply], which admits ordinary record changes only.
     */
    suspend fun applyHost(id: ProcessId, command: ProcessCommand): ProcessReduction = reduceStored(id, command)

    private suspend fun reduceStored(id: ProcessId, command: ProcessCommand): ProcessReduction {
        val isVersioned = isVersioningEnabled()
        var outcome: ProcessReduction = ProcessReduction.Rejected(ProcessRejection.UnknownProcess)
        data.update(STORAGE_KEY) { current ->
            val records = decode(current)
            val record = records.firstOrNull { it.id == id }
            if (record == null) {
                outcome = ProcessReduction.Rejected(ProcessRejection.UnknownProcess)
                current
            } else {
                val reduction = reduceApproved(record, command, isVersioned)
                outcome = reduction
                if (reduction is ProcessReduction.Changed) {
                    encode(records.map { if (it.id == id) reduction.record else it }, reduction.record)
                } else {
                    current
                }
            }
        }
        logReduction(command, outcome)
        return outcome
    }

    private fun logReduction(command: ProcessCommand, result: ProcessReduction) {
        val summary = "process command ${command::class.simpleName.orEmpty()} -> ${result::class.simpleName.orEmpty()}"
        if (result is ProcessReduction.Changed && command !is ProcessCommand.SettleWorkflow) {
            log.i { summary }
        } else {
            log.v { summary }
        }
    }

    /** Cleanup of old work remains possible; every new continuation requires current approved logic. */
    private fun isDefinitionAllowed(record: ProcessRecord, command: ProcessCommand, isVersioned: Boolean): Boolean =
        when (command) {
            is ProcessCommand.MigrateDefinition -> (isVersioned || record.definition != null) &&
                (definition == null || command.next == definition)

            is ProcessCommand.Finish,
            is ProcessCommand.Close,
            is ProcessCommand.CancelWorkflow,
            is ProcessCommand.SettleWorkflow,
            is ProcessCommand.ConfirmWrite,
            is ProcessCommand.MarkWriteUnknown,
            is ProcessCommand.WriteNotApplied,
            -> true

            is ProcessCommand.StartWorkflow -> true

            // The host command carries its own immutable admission binding.

            is ProcessCommand.Start,
            is ProcessCommand.Wait,
            is ProcessCommand.WaitCompletion,
            is ProcessCommand.InputChange,
            is ProcessCommand.Grant,
            is ProcessCommand.Revoke,
            is ProcessCommand.BeginWrite,
            -> definition == null || record.definition == definition || (!isVersioned && record.definition == null)
        }

    private fun reduceApproved(record: ProcessRecord, command: ProcessCommand, isVersioned: Boolean): ProcessReduction =
        if (isDefinitionAllowed(record, command, isVersioned)) {
            record.reduce(command, clock.now())
        } else {
            ProcessReduction.Rejected(ProcessRejection.DefinitionMismatch)
        }

    override suspend fun remove(id: ProcessId): Boolean {
        val isRemoved = gate.withLock { removeStored(id) }
        if (isRemoved) releaseSettled()
        return isRemoved
    }

    private suspend fun removeStored(id: ProcessId): Boolean {
        releaseSettled()
        val before = get(id)
        if (before != null && !canRemove(before)) return false
        if (before != null && shouldArchive(before)) archiveRecord(before)
        var isStored = false
        var isRemoved = false
        data.update(STORAGE_KEY) { current ->
            val records = decode(current)
            val record = records.firstOrNull { it.id == id }
            isStored = record != null
            isRemoved = record == null || (record == before && record.isRemovable())
            when {
                record == null || !isRemoved -> current
                records.size == 1 -> null
                else -> encode(records - record, null)
            }
        }
        logRemoval(isRemoved, isStored)
        return isRemoved
    }

    private fun logRemoval(isRemoved: Boolean, isStored: Boolean) {
        when {
            isRemoved && isStored -> log.i { "process removed" }
            !isRemoved -> log.v { "process removal refused" }
            else -> log.v { "process to remove is not stored" }
        }
    }

    private suspend fun canRemove(record: ProcessRecord): Boolean = record.isRemovable() && canRemoveWorkflow(record)

    override suspend fun archive(id: ProcessId): ArchivedProcess? = gate.withLock {
        releaseSettled()
        val record = get(id) ?: return@withLock null
        if (!record.isRemovable() || !canRemoveWorkflow(record)) return@withLock null
        check(shouldArchive(record)) { "Durable process archives are disabled" }
        archiveRecord(record)
    }

    override suspend fun archived(after: ProcessArchiveRef?, limit: Int, process: ProcessId?): ProcessArchivePage {
        list() // Live scoped access is required, including for an empty archive.
        val page = checkNotNull(archives).listOwned(checkNotNull(owner), after, limit, process)
        list() // Retiring an activation during protected IO must also revoke the returned data.
        return page
    }

    override suspend fun readArchive(ref: ProcessArchiveRef): ArchivedProcess? {
        list()
        val snapshot = checkNotNull(archives).getOwned(checkNotNull(owner), ref)
        list()
        return snapshot
    }

    override suspend fun restore(ref: ProcessArchiveRef): ProcessRecord? = gate.withLock {
        list()
        val archived = checkNotNull(archives).getOwned(checkNotNull(owner), ref) ?: return@withLock null
        val source = archived.record.forRestore()
        val existing = get(source.id)
        if (existing != null) {
            check(existing.incarnation == source.incarnation) { "Another process lifetime is active" }
            return@withLock existing
        }
        makeRoom()
        var restored: ProcessRecord? = null
        data.update(STORAGE_KEY) { current ->
            val records = decode(current)
            val found = records.firstOrNull { it.id == source.id }
            if (found != null) {
                check(found.incarnation == source.incarnation) { "Another process lifetime is active" }
                restored = found
                current
            } else {
                require(records.size < ProcessLimits.PROCESSES) { "Too many processes" }
                restored = source
                encode(records + source, source)
            }
        }
        log.i { "process restored from protected archive" }
        checkNotNull(restored)
    }

    private fun ProcessRecord.forRestore(): ProcessRecord {
        val running = phase as? ProcessPhase.Running ?: return this
        val workflow = running.attempt.workflow ?: return this
        val attempt = running.attempt.copy(workflow = workflow.copy(isDispatchClosed = true))
        return copy(phase = ProcessPhase.Running(attempt))
    }

    /** Quota reclamation only removes terminal records after archive confirmation. */
    private suspend fun makeRoom() {
        val records = list()
        if (records.size < ProcessLimits.PROCESSES) return
        if (archives?.isEnabled() != true && records.none { it.definition != null }) return
        for (record in records) {
            if (shouldArchive(record) && removeStored(record.id)) {
                releaseSettled()
                return
            }
        }
    }

    private suspend fun shouldArchive(record: ProcessRecord): Boolean {
        val storage = archives
        check(storage != null || record.definition == null) { "Protected process archive is unavailable" }
        return record.definition != null || storage?.isEnabled() == true
    }

    private suspend fun archiveRecord(record: ProcessRecord): ArchivedProcess = checkNotNull(archives).store(
        checkNotNull(owner),
        record,
        ProcessArchiveReason.Completed,
    )

    private fun ProcessRecord.isRemovable(): Boolean = phase is ProcessPhase.Ended && unresolvedWrites().isEmpty()

    private suspend fun canRemoveWorkflow(record: ProcessRecord): Boolean {
        val storage = runs ?: return true
        val harness = owner ?: return true
        val hasRunning = storage.load().any { run ->
            val key = run.process?.key
            run.harness == harness && key?.process == record.id && key.incarnation == record.incarnation &&
                run.status == WorkflowStatus.Running
        }
        if (hasRunning) return false
        return record.history.mapNotNull { entry ->
            entry.attempt.workflow?.let { entry to it }
        }.all { (entry, workflow) ->
            val stored = storage.find(workflow.run)
            val isNeverDispatched = stored == null && !workflow.isDispatchClosed &&
                entry.outcome == AttemptOutcome.Failed(AttemptFailure.Cancelled)
            workflow.isSettled || isNeverDispatched || (stored != null && stored.status != WorkflowStatus.Running)
        }
    }

    private suspend fun releaseSettled() {
        val storage = runs ?: return
        val harness = owner ?: return
        val records = list().associateBy { it.id }
        val terminal = storage.load().filter {
            it.process != null && it.harness == harness && !it.isProcessReleased && it.status != WorkflowStatus.Running
        }
        for (run in terminal) {
            val binding = checkNotNull(run.process)
            val record = records[binding.key.process]
            val isCurrent = record?.incarnation == binding.key.incarnation &&
                record.inFlight?.number == binding.key.attempt
            if (!isCurrent && record?.incarnation == binding.key.incarnation) {
                val receipt = ProcessWorkflow(run.id, binding.script, binding.digest)
                val result = reduceStored(record.id, ProcessCommand.SettleWorkflow(binding.key, receipt))
                check(result !is ProcessReduction.Rejected) { "Workflow cleanup receipt conflicts with the process" }
            }
            if (!isCurrent && !storage.releaseProcess(run.id)) error("Process workflow retention is unavailable")
        }
    }

    private fun decode(element: JsonElement?): List<ProcessRecord> = if (element == null) {
        emptyList()
    } else {
        guarded {
            decodeStored(element)
        }
    }

    /** [changed] is the one record this write adds or replaces: it must stay within its proven size ceiling. */
    private fun encode(records: List<ProcessRecord>, changed: ProcessRecord?): JsonElement {
        if (changed != null) {
            val size = Json.encodeToJsonElement(ProcessRecord.serializer(), changed).toString().encodeToByteArray().size
            require(size <= ProcessLimits.RECORD_BYTES) { "Process record is too large" }
        }
        return Json.encodeToJsonElement(StoredProcesses.serializer(), StoredProcesses(VERSION, records))
    }

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
    private data class StoredProcesses(val v: Int, val records: List<ProcessRecord>) {
        override fun toString(): String = "StoredProcesses(***)"
    }

    internal companion object {
        /** Trusted host read used to reconcile terminal workflow retention after process changes. */
        fun decodeStored(element: JsonElement?): List<ProcessRecord> {
            if (element == null) return emptyList()
            val stored = Json.decodeFromJsonElement(StoredProcesses.serializer(), element)
            check(stored.v == VERSION) { "Unsupported process version" }
            check(stored.records.size <= ProcessLimits.PROCESSES) { "Too many stored processes" }
            check(stored.records.map { it.id }.toSet().size == stored.records.size) { "Duplicate process" }
            return stored.records
        }

        /** Host-only CAS encoding for native cleanup receipts; ordinary script writes use the scoped service. */
        fun encodeStored(records: List<ProcessRecord>): JsonElement {
            check(records.size <= ProcessLimits.PROCESSES)
            for (record in records) {
                val bytes = Json.encodeToString(ProcessRecord.serializer(), record).encodeToByteArray().size
                require(bytes <= ProcessLimits.RECORD_BYTES) { "Process record is too large" }
            }
            return Json.encodeToJsonElement(StoredProcesses.serializer(), StoredProcesses(VERSION, records))
        }

        /**
         * The one `ScriptData` key that holds every process of a harness. Reserved: a script must not use it, and its
         * name is specific enough not to be picked by accident.
         */
        const val STORAGE_KEY = "hb_processes"

        private const val VERSION = 1
    }
}
