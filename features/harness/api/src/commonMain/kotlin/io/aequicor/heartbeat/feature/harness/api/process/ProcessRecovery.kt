package io.aequicor.heartbeat.feature.harness.api.process

import kotlin.time.Instant

/**
 * Something a process left open when the run that wrote it ended. After a restart the harness settles each item
 * before it moves on: nothing is restarted or repeated on a guess, because the record alone cannot tell what the
 * outside world did in the meantime.
 */
public sealed interface ProcessRecovery {
    /**
     * [attempt] was in flight and its executor was attached. Nobody knows whether it still runs, finished or died:
     * ask the executor's owner (the attempt names it), then close the attempt with [ProcessCommand.Finish]; an
     * attempt that cannot be accounted for is closed as [AttemptFailure.Interrupted].
     */
    public data class AttemptOpen(val attempt: ProcessAttempt) : ProcessRecovery

    /**
     * [write] was journaled and its outcome is still open, so the external system may or may not have changed. Mark
     * it [ProcessCommand.MarkWriteUnknown], look at the external system (what is there, not what was meant to be
     * written) and settle it with [ProcessCommand.ConfirmWrite] or [ProcessCommand.WriteNotApplied]. Repeating
     * [ProcessCommand.BeginWrite] before that is refused.
     */
    public data class WriteOpen(val write: ExternalWrite) : ProcessRecovery

    /**
     * The process waits on [wait]. A deadline may have passed ([isDue]) and an event may have been missed while the
     * profile was not running: check the state the wait was about before waiting on, then [ProcessCommand.Resume]
     * or keep waiting.
     */
    public data class WaitOpen(val wait: ProcessWait, val isDue: Boolean) : ProcessRecovery
}

/**
 * What this process left open as of [now], in the order they should be settled: the attempt in flight, then the open
 * writes oldest first, then the wait. Empty when nothing needs reconciling. An ended process can still have open
 * writes, which stay listed until they are settled.
 */
public fun ProcessRecord.recovery(now: Instant): List<ProcessRecovery> = buildList {
    inFlight?.let { add(ProcessRecovery.AttemptOpen(it)) }
    unresolvedWrites().forEach { add(ProcessRecovery.WriteOpen(it)) }
    (phase as? ProcessPhase.Waiting)?.wait?.let { wait ->
        val deadline = wait.condition.deadline
        add(ProcessRecovery.WaitOpen(wait, isDue = deadline != null && deadline <= now))
    }
}
