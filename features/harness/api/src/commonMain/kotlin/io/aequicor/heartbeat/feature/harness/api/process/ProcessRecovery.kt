package io.aequicor.heartbeat.feature.harness.api.process

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Something a process left open when the run that wrote it ended. After a restart the harness settles each item
 * before it moves on: nothing is restarted or repeated on a guess, because the record alone cannot tell what the
 * outside world did in the meantime.
 *
 * The list cannot tell an item left behind by an earlier run from one a live caller is working on right now, and a
 * script cannot tell a restart from a source replacement or from another item's activation either: in all of these
 * a call of an earlier activation may still be running. What separates them is time. Every attempt and external call
 * of a harness is bounded by a timeout, so one that was last changed longer ago than the longest such call
 * (`settleAfter`) has no live caller any more, while a younger one may still be running and must not be judged by
 * looking at the external system. Pass that delay to [recovery]: younger attempts and writes are not listed, and the
 * caller looks again once the delay has passed.
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
     * written) and settle it with [ProcessCommand.ConfirmWrite] or [ProcessCommand.WriteNotApplied]. The mark and
     * "not applied" name the try ([ExternalWrite.tries]) that was looked at; a confirmation does not need to,
     * because it states what the external system holds. Repeating [ProcessCommand.BeginWrite] before that is refused.
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
 * writes oldest first, then the wait. An attempt (by its `startedAt`) or a write (by its last change, which a
 * [ProcessCommand.MarkWriteUnknown] renews) that changed less than [settleAfter] before [now] is not listed yet: a
 * call may still be running, so look again after the delay. A wait is no call and is always listed. An ended process
 * can still have open writes, which stay listed until they are settled. Keeping a wait whose condition still holds,
 * or an attempt whose executor is alive, is a valid settlement that changes nothing, so those items stay listed on
 * every call: do not wait for the list to become empty. As in [reduce], a [now] before [ProcessRecord.updatedAt] is
 * clamped to it.
 */
public fun ProcessRecord.recovery(now: Instant, settleAfter: Duration = Duration.ZERO): List<ProcessRecovery> =
    buildList {
        require(!settleAfter.isNegative()) { "Invalid settle delay" }
        val at = maxOf(now, updatedAt)
        val cutoff = at - settleAfter
        inFlight?.takeIf { it.startedAt <= cutoff }?.let { add(ProcessRecovery.AttemptOpen(it)) }
        unresolvedWrites().filter { it.at <= cutoff }.forEach { add(ProcessRecovery.WriteOpen(it)) }
        (phase as? ProcessPhase.Waiting)?.wait?.let { wait ->
            val deadline = wait.condition.deadline
            add(ProcessRecovery.WaitOpen(wait, isDue = deadline != null && deadline <= at))
        }
    }
