package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.harness.api.process.ArchivedProcess
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchivePage
import io.aequicor.heartbeat.feature.harness.api.process.ProcessArchiveRef
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessLimits
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction

/**
 * Durable development processes of this harness: stage, attempt, executor, wait, result evidence and the journal of
 * external writes, kept across restarts independently of any chat or live session. The host fixes the harness owner;
 * scripts cannot address another harness.
 *
 * The records of all processes live in the harness's [ScriptData] namespace in one reserved key, `hb_processes`, so
 * they share its quotas and its access rules: every call requires a live, published approved activation, and a process
 * outlives source replacement, detach, disable and restart. Do not use that key with [ScriptData] directly. A
 * harness keeps at most [ProcessLimits.PROCESSES] processes. Stage graphs, GitHub rules and texts stay in the
 * harness; the platform only guarantees that a record is consistent and a change is atomic within this profile.
 * Two machines may independently store the same id. These calls do not acquire shared ownership; neither copying
 * a record nor moving a PR description transfers a workflow or stops a native executor. The host-only ownership
 * contract lives in `process.ProcessOwnership` and requires a shared coordinator before execution is connected.
 *
 * Every change goes through a [ProcessCommand], which the host reduces against the freshly stored record and writes
 * with a compare-and-set; a concurrent writer makes the host re-read and reduce again, never overwrite. Read before
 * you decide, but never rely on a record you read earlier: send the command that expresses your decision and handle
 * the reduction. Identifiers, results and journal entries must never be logged. Stored processes that cannot be
 * read (written by a newer version, damaged) are reported as `IllegalStateException` without their content; they
 * are never silently skipped or overwritten, and because they share one key the whole set is unreadable then; the
 * only way out is `data.write("hb_processes", null, revision)`, which deletes every process of the harness (settle
 * the external systems by hand first). The key is reserved by convention only: a script that writes it can bypass the
 * journal, so it is a convention of one harness, not a security boundary. Because the namespace quota is shared with
 * the script's own data, a change that would exceed it throws `IllegalArgumentException` instead of returning a
 * [ProcessReduction].
 *
 * New records pin their owning script and approved harness revision in [ProcessRecord.definition]. Updated code
 * can read old records and settle old work, but cannot start or resume stages until explicitly accepting its own
 * current revision with [ProcessCommand.MigrateDefinition]. Migration requires no live attempt or unresolved write;
 * it preserves workflow journals and evidence. Unknown [ProcessRecord.schemaVersion] fails closed on read.
 */
public interface ScriptProcesses {
    /** The stored record, or null if the process was never opened or has been removed. */
    public suspend fun get(id: ProcessId): ProcessRecord?

    /** Every stored process in the order they were opened. */
    public suspend fun list(): List<ProcessRecord>

    /**
     * Opens [id] at [inputRevision] unless it already exists, and returns the stored record either way. An existing
     * process is returned as it is: use [ProcessCommand.ReviseInput] to move its input. Throws
     * IllegalArgumentException when [ProcessLimits.PROCESSES] processes are already stored and no eligible ended
     * record can be archived to free a slot in durable mode. Archive failure preserves the active original.
     */
    public suspend fun open(id: ProcessId, inputRevision: Long = 0): ProcessRecord

    /**
     * Reduces [command] against the stored record of [id] and, when it changes the record, persists the result before
     * returning. [ProcessReduction.Changed] is durable; [ProcessReduction.Unchanged] means the record already
     * reflected the command (an exact replay) or the command spoke about something that is already over, such as an
     * older try of a write; [ProcessReduction.Rejected] leaves the record as it was, including
     * [io.aequicor.heartbeat.feature.harness.api.process.ProcessRejection.UnknownProcess] for a process that is not
     * stored.
     *
     * Workflow reservations and their cancellation are issued only by the host through the keyed workflow API, so a
     * script cannot pass them here: the type admits ordinary record changes only.
     */
    public suspend fun apply(id: ProcessId, command: ProcessCommand.Record): ProcessReduction

    /**
     * Forgets an ended process whose external writes are all settled, and returns true; also true when [id] is not
     * stored. False means the process was kept because it is still running or a write is unresolved. With durable
     * mode enabled, or for a pinned record, removal first confirms an immutable protected archive. Archive failure
     * leaves the active original; a concurrent raw namespace edit also prevents removing a different snapshot.
     */
    public suspend fun remove(id: ProcessId): Boolean

    /**
     * Confirms an immutable snapshot of an ended process with settled external writes and completed workflow
     * cleanup. The active record stays until remove; null means missing or not yet eligible. Requires durable mode
     * or an already pinned process. Failure never removes the active original.
     */
    public suspend fun archive(id: ProcessId): ArchivedProcess? = error("Process archives are unavailable")

    /** Archived snapshots of this harness, with stable reference pagination and optional exact process search. */
    public suspend fun archived(
        after: ProcessArchiveRef? = null,
        limit: Int = 20,
        process: ProcessId? = null,
    ): ProcessArchivePage = error("Process archives are unavailable")

    /** Resolves a snapshot of this harness; other owners and other profiles are inaccessible here. */
    public suspend fun readArchive(ref: ProcessArchiveRef): ArchivedProcess? = error("Process archives are unavailable")

    /**
     * Restores this harness's immutable snapshot into active storage without removing the archive. Preserves its
     * lifetime, counters, waits and workflow keys, so native execution is never silently redispatched. Existing
     * state of that same lifetime is returned without rewinding; a different active lifetime conflicts. No executor
     * is started. A restored native reservation cannot recreate a missing workflow journal: keyed start refuses
     * and observation/cancellation remain unavailable until the native result is reconciled. Reconcile open
     * attempts/writes and explicitly migrate changed logic before continuing. Capacity
     * or namespace quota failure preserves the snapshot. Null means no owned snapshot with this reference.
     */
    public suspend fun restore(ref: ProcessArchiveRef): ProcessRecord? = error("Process archives are unavailable")
}
