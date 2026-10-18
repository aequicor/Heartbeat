package io.aequicor.heartbeat.feature.harness.api.process

import kotlin.time.Instant

/**
 * Something a process left open when the run that wrote it ended. After a restart the harness settles each item
 * before it moves on: nothing is restarted or repeated on a guess, because the record alone cannot tell what the
 * outside world did in the meantime.
 *
 * The list cannot tell an item left behind by an earlier run from one a live caller is working on right now, so ask
 * for it only when no live code or executor of the harness can still change the record: once, in the one script item
 * that owns the process, when its activation starts and before it begins new work. An activation also happens
 * without a restart (a source replacement, another item of the harness), and then attempts and calls are alive.
 */
public sealed interface ProcessRecovery {
    /**
     * [attempt] was in flight and its executor was attached. Nobody knows whether it still runs, finished or died:
     * ask the executor's owner (the attempt names it) and act on the answer. An executor that survives a restart and
     * still runs (a workflow run) stays as it is: leave the attempt in flight and [ProcessCommand.Finish] it with the
     * executor's own outcome when it reports. One that finished is closed with that outcome. One that is gone, cannot
     * outlive a restart (a local command, work inside the script) or whose owner cannot say is closed with
     * `Finish(attempt.number, AttemptOutcome.Failed(AttemptFailure.Interrupted))`. Interrupting an attempt that still
     * runs discards its real result: a later [ProcessCommand.Finish] of it is an outcome conflict.
     */
    public data class AttemptOpen(val attempt: ProcessAttempt) : ProcessRecovery

    /**
     * [write] was journaled and its outcome is still open, so the external system may or may not have changed. Mark
     * it [ProcessCommand.MarkWriteUnknown], look at the external system (what is there, not what was meant to be
     * written) and settle it with [ProcessCommand.ConfirmWrite] or [ProcessCommand.WriteNotApplied]; both of the
     * latter name the try ([ExternalWrite.tries]) that was looked at. Repeating [ProcessCommand.BeginWrite] before
     * that is refused.
     */
    public data class WriteOpen(val write: ExternalWrite) : ProcessRecovery

    /**
     * The process waits on [wait]. A deadline may have passed ([isDue]) and an event may have been missed while the
     * profile was not running: check the state the wait was about before waiting on, then
     * `Resume(wait.number)` ([ProcessCommand.Resume]) or keep waiting.
     */
    public data class WaitOpen(val wait: ProcessWait, val isDue: Boolean) : ProcessRecovery
}

/**
 * What this process left open as of [now], in the order they should be settled: the attempt in flight, then the open
 * writes oldest first, then the wait. Empty when nothing is open. An ended process can still have open writes, which
 * stay listed until they are settled. Keeping a wait whose condition still holds, or an attempt whose executor is
 * alive, is a valid settlement that changes nothing, so those items stay listed on every call: do not wait for the
 * list to become empty. As in [reduce], a [now] before [ProcessRecord.updatedAt] is clamped to it.
 */
public fun ProcessRecord.recovery(now: Instant): List<ProcessRecovery> = buildList {
    val at = maxOf(now, updatedAt)
    inFlight?.let { add(ProcessRecovery.AttemptOpen(it)) }
    unresolvedWrites().forEach { add(ProcessRecovery.WriteOpen(it)) }
    (phase as? ProcessPhase.Waiting)?.wait?.let { wait ->
        val deadline = wait.condition.deadline
        add(ProcessRecovery.WaitOpen(wait, isDue = deadline != null && deadline <= at))
    }
}
