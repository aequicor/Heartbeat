package io.aequicor.heartbeat.feature.harness.api.script

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
 * harness; the platform only guarantees that a record is consistent and a change is atomic.
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
 */
public interface ScriptProcesses {
    /** The stored record, or null if the process was never opened or has been removed. */
    public suspend fun get(id: ProcessId): ProcessRecord?

    /** Every stored process in the order they were opened. */
    public suspend fun list(): List<ProcessRecord>

    /**
     * Opens [id] at [inputRevision] unless it already exists, and returns the stored record either way. An existing
     * process is returned as it is: use [ProcessCommand.ReviseInput] to move its input. Throws
     * IllegalArgumentException when [ProcessLimits.PROCESSES] processes are already stored.
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
     * stored. False means the process was kept because it is still running or a write is unresolved.
     */
    public suspend fun remove(id: ProcessId): Boolean
}
