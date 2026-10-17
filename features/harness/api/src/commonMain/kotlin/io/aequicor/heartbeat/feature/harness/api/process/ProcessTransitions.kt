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
 * | [Wait] | Idle | Waiting (no executor attached) |
 * | [Resume] | Waiting | Idle |
 * | [ReviseInput] / [ObserveSubject] | Idle, Running, Waiting | same phase, newer input or subject |
 * | [Close] | Idle, Running (attempt abandoned), Waiting | Ended |
 *
 * Exact replays are answered with [ProcessReduction.Unchanged] where the record alone proves them: a [Finish] of an
 * already closed attempt with the same outcome, a [ReviseInput] to the current revision, an [ObserveSubject] of the
 * current subject, a [Close] with the verdict already reached. Everything else that does not fit is rejected.
 */
public sealed interface ProcessCommand {
    /** Starts attempt number [ProcessRecord.nextAttempt] of [stage] on [executor]. */
    public data class Start(val stage: StageName, val executor: ExecutorRef) : ProcessCommand

    /** Closes the in-flight attempt [attempt] with [outcome]. */
    public data class Finish(val attempt: Int, val outcome: AttemptOutcome) : ProcessCommand

    /** Parks the idle process on [condition]; the executor of the previous attempt has been released by [Finish]. */
    public data class Wait(val reason: WaitReason, val condition: WakeCondition) : ProcessCommand

    /** Ends the current wait; whether it was satisfied or timed out is for the harness to find out. */
    public data object Resume : ProcessCommand

    /** Records that the process input is now at [revision]; revisions only grow. */
    public data class ReviseInput(val revision: Long) : ProcessCommand

    /** Records that the subject under work is now [sha]. */
    public data class ObserveSubject(val sha: SubjectSha) : ProcessCommand

    /** Ends the process with [end]; an in-flight attempt is closed as [AttemptFailure.Cancelled]. */
    public data class Close(val end: ProcessEnd) : ProcessCommand
}

/** Why [reduce] refused a command. The record is left as it was. */
public enum class ProcessRejection {
    /** The process has ended. */
    Closed,

    /** An attempt is in flight: finish it before starting, waiting or leaving it. */
    AttemptInFlight,

    /** The process is waiting: resume it first. */
    WaitPending,

    /** The process is not waiting. */
    NotWaiting,

    /** The attempt number was never issued or is no longer retained. */
    UnknownAttempt,

    /** The attempt is already closed with a different outcome. */
    OutcomeConflict,

    /** The input revision is older than the one already recorded. */
    StaleRevision,

    /** The process would use more than [ProcessLimits.STAGES] distinct stages. */
    TooManyStages,
}

/** Result of [reduce]. */
public sealed interface ProcessReduction {
    /** The command changed the record; persist [record]. */
    public data class Changed(val record: ProcessRecord) : ProcessReduction

    /** The command was already reflected in [record]; there is nothing to persist. */
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
        // Replays of closed attempts and of the final verdict stay answerable after the end.
        is ProcessCommand.Finish -> finish(command, now)

        is ProcessCommand.Close -> close(command.end, now)

        is ProcessCommand.Start -> whileLive { start(command, now) }

        is ProcessCommand.Wait -> whileLive { wait(command, now) }

        ProcessCommand.Resume -> whileLive { resume(now) }

        is ProcessCommand.ReviseInput -> whileLive { reviseInput(command.revision, now) }

        is ProcessCommand.ObserveSubject -> whileLive { observeSubject(command.sha, now) }
    }
}

/** Runs [reduction] unless the process has ended, which refuses every command that is not a replay. */
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
    ProcessPhase.Idle -> changed(
        copy(phase = ProcessPhase.Waiting(ProcessWait(command.reason, command.condition, now)), updatedAt = now),
    )

    is ProcessPhase.Running -> rejected(ProcessRejection.AttemptInFlight)

    is ProcessPhase.Waiting -> rejected(ProcessRejection.WaitPending)

    is ProcessPhase.Ended -> rejected(ProcessRejection.Closed)
}

private fun ProcessRecord.resume(now: Instant): ProcessReduction = if (phase is ProcessPhase.Waiting) {
    changed(copy(phase = ProcessPhase.Idle, updatedAt = now))
} else {
    rejected(ProcessRejection.NotWaiting)
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

private fun changed(record: ProcessRecord): ProcessReduction = ProcessReduction.Changed(record)

private fun rejected(reason: ProcessRejection): ProcessReduction = ProcessReduction.Rejected(reason)
