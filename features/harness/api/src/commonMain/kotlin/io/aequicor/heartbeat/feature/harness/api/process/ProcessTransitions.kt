package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlin.time.Instant

/**
 * Every way the record of a process changes. Commands are pure inputs of [reduce]: they carry no clock and cause no IO.
 * Who sends them, when and how a persisted result is written is up to the layer that owns storage and recovery.
 *
 * | Command | From | To |
 * |---|---|---|
 * | [Start] | Idle | Running (new attempt bound to the current input revision and subject) |
 * | [Finish] | Running (that attempt) | Idle, attempt closed with the outcome |
 * | [Wait] | Idle | Waiting (no executor attached), numbered |
 * | [Resume] | Waiting (that wait) | Idle |
 * | [ReviseInput] / [ObserveSubject] | Idle, Running, Waiting | same phase, newer input or subject |
 * | [Grant] / [Revoke] | Idle, Running, Waiting | same phase, authority added or removed |
 * | [BeginWrite] | Idle, Running, Waiting | same phase, write journaled as Intended (needs the authority) |
 * | [ConfirmWrite] / [MarkWriteUnknown] / [WriteNotApplied] | any, also Ended | same phase, write settled or marked |
 * | [Close] | Idle, Running (attempt abandoned), Waiting | Ended |
 *
 * Exact replays are answered with [ProcessReduction.Unchanged] where the record alone proves them: a [Finish] of an
 * already closed attempt with the same outcome, a [Resume] of a wait that is already over, a [ReviseInput] to the
 * current revision, an [ObserveSubject] of the current subject, a [BeginWrite] of a confirmed write, a settlement
 * that is already recorded, a [Close] with the verdict already reached. Everything else that does not fit is rejected.
 * After [Close] only [Finish], [Resume], [Close] and the write settlements are still answered; any other command,
 * an exact replay of [ReviseInput] or [ObserveSubject] included, is [ProcessRejection.Closed].
 *
 * External writes never repeat blindly. [BeginWrite] journals the intent before the caller touches the external
 * system. The same key is refused while its outcome is open ([ProcessRejection.WriteUnresolved]): after a restart the
 * caller marks the write unknown ([MarkWriteUnknown]), looks at the external system and settles it with
 * [ConfirmWrite] (it is there) or [WriteNotApplied] (it is not, and only then may [BeginWrite] try again). A mark or
 * a "not applied" names the try ([ExternalWrite.tries]) the caller looked at: a decision about an older try is
 * [ProcessReduction.Unchanged] and never touches a newer one, so a late or concurrent reconciler cannot turn a
 * retry that is already under way into a second external call.
 *
 * The journal remembers [ProcessLimits.WRITES] keys. When it is full the oldest settled write is forgotten (a write
 * known not to have happened first), and beginning a forgotten key is a new write. Idempotency therefore holds for
 * the keys still in the journal: name the operation in the key (a revision, a head) so that a replay of it arrives
 * while its key is still remembered.
 */
public sealed interface ProcessCommand {
    /** Starts attempt number [ProcessRecord.nextAttempt] of [stage] on [executor]. */
    public data class Start(val stage: StageName, val executor: ExecutorRef) : ProcessCommand

    /** Closes the in-flight attempt [attempt] with [outcome]. */
    public data class Finish(val attempt: Int, val outcome: AttemptOutcome) : ProcessCommand

    /** Parks the idle process on [condition]; the executor of the previous attempt has been released by [Finish]. */
    public data class Wait(val reason: WaitReason, val condition: WakeCondition) : ProcessCommand

    /**
     * Ends wait number [wait]; whether it was satisfied or timed out is for the harness to find out. A duplicate
     * that names a wait which is already over changes nothing, so it can never end a newer wait.
     */
    public data class Resume(val wait: Int) : ProcessCommand

    /** Records that the process input is now at [revision]; revisions only grow. */
    public data class ReviseInput(val revision: Long) : ProcessCommand

    /** Records that the subject under work is now [sha]. */
    public data class ObserveSubject(val sha: SubjectSha) : ProcessCommand

    /** Gives the process [authority] for new external writes. Writes already journaled are not affected. */
    public data class Grant(val authority: ProcessAuthority) : ProcessCommand

    /** Takes [authority] away from new external writes; a write that is already open must still be settled. */
    public data class Revoke(val authority: ProcessAuthority) : ProcessCommand

    /** Journals the intent to write [target] under [key] with [authority], before the external system is touched. */
    public data class BeginWrite(val key: WriteKey, val authority: ProcessAuthority, val target: ResultRef) :
        ProcessCommand

    /** The write [key] took effect; [result] is the evidence. Also settles a write that was marked unknown. */
    public data class ConfirmWrite(val key: WriteKey, val result: ResultRef) : ProcessCommand

    /**
     * Whether try [tries] of write [key] happened is not known: a restart or timeout cut the call short. It must be
     * reconciled. [tries] is [ExternalWrite.tries] of the write the caller looked at.
     */
    public data class MarkWriteUnknown(val key: WriteKey, val tries: Int) : ProcessCommand

    /** Try [tries] of write [key] is known not to have taken effect, so the write may be begun again. */
    public data class WriteNotApplied(val key: WriteKey, val tries: Int) : ProcessCommand

    /** Ends the process with [end]; an in-flight attempt is closed as [AttemptFailure.Cancelled]. */
    public data class Close(val end: ProcessEnd) : ProcessCommand
}

/** Why a command was refused: by [reduce], or by the store before a record could be reduced. Nothing changed. */
public enum class ProcessRejection {
    /** No such process is stored. Only the store produces this; [reduce] always has a record. */
    UnknownProcess,

    /** The process has ended. */
    Closed,

    /** An attempt is in flight: finish it before starting, waiting or leaving it. */
    AttemptInFlight,

    /** The process is waiting: resume it first. */
    WaitPending,

    /** The wait number was never issued. */
    UnknownWait,

    /** The attempt number was never issued or is no longer retained. */
    UnknownAttempt,

    /** The attempt or the write is already settled with a different outcome, or cannot take this one. */
    OutcomeConflict,

    /** The input revision is older than the one already recorded. */
    StaleRevision,

    /** The process would use more than [ProcessLimits.STAGES] distinct stages. */
    TooManyStages,

    /** The process was not granted the authority the write needs. */
    NotAuthorized,

    /** The write key is open: its outcome must be reconciled before the same write may be considered again. */
    WriteUnresolved,

    /** The write key is already used for another authority or target. */
    WriteKeyConflict,

    /** The try number of the write was never issued (tries start at 1). */
    UnknownTry,

    /** No write with that key is journaled. */
    UnknownWrite,

    /** Every journaled write is still unresolved, so there is no room for a new one. */
    TooManyWrites,
}

/** Result of [reduce]. */
public sealed interface ProcessReduction {
    /** The command changed the record; persist [record]. */
    public data class Changed(val record: ProcessRecord) : ProcessReduction

    /**
     * The command was already reflected in [record], or it spoke about something that is already over (a wait that
     * has ended, an older try of a write): there is nothing to persist. It does not say that [record] holds what the
     * command asked for; a caller that must act on the current state reads it from [record].
     */
    public data class Unchanged(val record: ProcessRecord) : ProcessReduction

    /** The command does not fit the current state. */
    public data class Rejected(val reason: ProcessRejection) : ProcessReduction
}

/**
 * Applies [command] at time [at]. The result never changes this record. Recorded times never go backwards: a clock
 * that did is clamped to [ProcessRecord.updatedAt].
 */
public fun ProcessRecord.reduce(command: ProcessCommand, at: Instant): ProcessReduction {
    val now = maxOf(at, updatedAt)
    return when (command) {
        // Outcomes that arrive late still belong in the record: replays of closed attempts and finished waits, the
        // final verdict and the settlement of writes stay answerable after the end.
        is ProcessCommand.Finish -> finish(command, now)

        is ProcessCommand.Close -> close(command.end, now)

        is ProcessCommand.Resume -> resume(command.wait, now)

        is ProcessCommand.ConfirmWrite -> confirmWrite(command.key, command.result, now)

        is ProcessCommand.MarkWriteUnknown -> markWriteUnknown(command.key, command.tries, now)

        is ProcessCommand.WriteNotApplied -> writeNotApplied(command.key, command.tries, now)

        is ProcessCommand.Start -> whileLive { start(command, now) }

        is ProcessCommand.Wait -> whileLive { wait(command, now) }

        is ProcessCommand.ReviseInput -> whileLive { reviseInput(command.revision, now) }

        is ProcessCommand.ObserveSubject -> whileLive { observeSubject(command.sha, now) }

        is ProcessCommand.Grant -> whileLive { grant(command.authority, now) }

        is ProcessCommand.Revoke -> whileLive { revoke(command.authority, now) }

        is ProcessCommand.BeginWrite -> whileLive { beginWrite(command, now) }
    }
}

/** Runs [reduction] unless the process has ended, which refuses every command that is not answered after the end. */
private inline fun ProcessRecord.whileLive(reduction: () -> ProcessReduction): ProcessReduction =
    if (phase is ProcessPhase.Ended) rejected(ProcessRejection.Closed) else reduction()

private fun ProcessRecord.observeSubject(sha: SubjectSha, now: Instant): ProcessReduction = if (sha == subject) {
    ProcessReduction.Unchanged(this)
} else {
    changed(copy(subject = sha, updatedAt = now))
}

private fun ProcessRecord.start(command: ProcessCommand.Start, now: Instant): ProcessReduction = when {
    phase is ProcessPhase.Running -> rejected(ProcessRejection.AttemptInFlight)

    phase is ProcessPhase.Waiting -> rejected(ProcessRejection.WaitPending)

    command.stage !in stages() && stages().size >= ProcessLimits.STAGES -> rejected(ProcessRejection.TooManyStages)

    else -> {
        val attempt = ProcessAttempt(nextAttempt, command.stage, command.executor, inputRevision, subject, now)
        changed(copy(phase = ProcessPhase.Running(attempt), nextAttempt = nextAttempt + 1, updatedAt = now))
    }
}

private fun ProcessRecord.finish(command: ProcessCommand.Finish, now: Instant): ProcessReduction {
    val running = inFlight
    val closed = history.firstOrNull { it.attempt.number == command.attempt }
    return when {
        running != null && running.number == command.attempt -> changed(
            copy(
                phase = ProcessPhase.Idle,
                history = history.retaining(AttemptRecord(running, command.outcome, now)),
                updatedAt = now,
            ),
        )

        closed == null -> rejected(ProcessRejection.UnknownAttempt)

        closed.outcome == command.outcome -> ProcessReduction.Unchanged(this)

        else -> rejected(ProcessRejection.OutcomeConflict)
    }
}

private fun ProcessRecord.wait(command: ProcessCommand.Wait, now: Instant): ProcessReduction = when (phase) {
    ProcessPhase.Idle -> {
        val wait = ProcessWait(nextWait, command.reason, command.condition, now)
        changed(copy(phase = ProcessPhase.Waiting(wait), nextWait = nextWait + 1, updatedAt = now))
    }

    is ProcessPhase.Running -> rejected(ProcessRejection.AttemptInFlight)

    is ProcessPhase.Waiting -> rejected(ProcessRejection.WaitPending)

    is ProcessPhase.Ended -> rejected(ProcessRejection.Closed)
}

private fun ProcessRecord.resume(number: Int, now: Instant): ProcessReduction {
    val waiting = (phase as? ProcessPhase.Waiting)?.wait
    return when {
        waiting != null && waiting.number == number -> changed(copy(phase = ProcessPhase.Idle, updatedAt = now))
        number in 1 until nextWait -> ProcessReduction.Unchanged(this)
        else -> rejected(ProcessRejection.UnknownWait)
    }
}

private fun ProcessRecord.reviseInput(revision: Long, now: Instant): ProcessReduction = when {
    revision < inputRevision -> rejected(ProcessRejection.StaleRevision)
    revision == inputRevision -> ProcessReduction.Unchanged(this)
    else -> changed(copy(inputRevision = revision, updatedAt = now))
}

private fun ProcessRecord.close(end: ProcessEnd, now: Instant): ProcessReduction {
    val ended = phase as? ProcessPhase.Ended
    val running = inFlight
    return when {
        ended != null -> if (ended.end == end) ProcessReduction.Unchanged(this) else rejected(ProcessRejection.Closed)

        running == null -> changed(copy(phase = ProcessPhase.Ended(end, now), updatedAt = now))

        else -> changed(
            copy(
                phase = ProcessPhase.Ended(end, now),
                history = history.retaining(
                    AttemptRecord(running, AttemptOutcome.Failed(AttemptFailure.Cancelled), now),
                ),
                updatedAt = now,
            ),
        )
    }
}

/**
 * Appends [closed] and trims to [ProcessLimits.HISTORY] by dropping the oldest records that are not evidence: the last
 * record overall and the latest success of every stage stay. At most [ProcessLimits.STAGES] + 1 records are
 * protected, which is below the limit, so a droppable record always exists.
 */
private fun List<AttemptRecord>.retaining(closed: AttemptRecord): List<AttemptRecord> {
    val all = this + closed
    val excess = all.size - ProcessLimits.HISTORY
    if (excess <= 0) return all
    val protected = HashSet<Int>()
    protected += closed.attempt.number
    val seen = HashSet<StageName>()
    for (record in all.asReversed()) {
        if (record.outcome is AttemptOutcome.Succeeded && seen.add(record.attempt.stage)) {
            protected += record.attempt.number
        }
    }
    var toDrop = excess
    return all.filter { record ->
        val isDropped = toDrop > 0 && record.attempt.number !in protected
        if (isDropped) toDrop--
        !isDropped
    }
}

internal fun changed(record: ProcessRecord): ProcessReduction = ProcessReduction.Changed(record)

internal fun rejected(reason: ProcessRejection): ProcessReduction = ProcessReduction.Rejected(reason)
