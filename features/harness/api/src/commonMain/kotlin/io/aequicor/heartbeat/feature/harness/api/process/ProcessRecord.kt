package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Why a process stopped moving on its own. */
@Serializable
public enum class WaitReason {
    /** A person has to answer or decide. */
    @SerialName("human")
    Human,

    /** A system outside the profile has to change, for example a review service or a hosting platform. */
    @SerialName("external")
    External,

    /** Another executor's result is awaited without keeping that executor's parent alive. */
    @SerialName("executor")
    Executor,

    /** Only time has to pass. */
    @SerialName("timer")
    Timer,
}

/**
 * A durable wait: [condition] says which events or deadline end it, [since] when it began. The wait is a fact about
 * the process, not about a live session, so nothing runs while it lasts and it survives restarts. [number] identifies
 * it for good: a late duplicate of "the answer arrived" names the wait it belongs to and cannot end a newer one.
 */
@Serializable
public data class ProcessWait(
    val number: Int,
    val reason: WaitReason,
    val condition: WakeCondition,
    val since: Instant,
) {
    init {
        require(number >= 1) { "Invalid wait number" }
    }
}

/**
 * One try at one stage by one executor. It is bound to the inputs it started from: [inputRevision] and [subject]
 * (null means the attempt does not depend on any subject), which decide later whether its result is still current.
 */
@Serializable
public data class ProcessAttempt(
    val number: Int,
    val stage: StageName,
    val executor: ExecutorRef,
    val inputRevision: Long,
    val subject: SubjectSha? = null,
    val startedAt: Instant,
) {
    init {
        require(number >= 1) { "Invalid attempt number" }
        require(inputRevision >= 0) { "Invalid input revision" }
    }
}

/** Why an attempt did not produce a result. */
@Serializable
public enum class AttemptFailure {
    /** The executor reported an error. */
    @SerialName("error")
    Error,

    /** The executor did not finish in time. */
    @SerialName("timeout")
    Timeout,

    /** The executor was lost, for example by a restart, and its outcome is unknown or cannot be recovered. */
    @SerialName("interrupted")
    Interrupted,

    /** The attempt was abandoned on purpose. */
    @SerialName("cancelled")
    Cancelled,
}

/** How a finished attempt ended. */
@Serializable
public sealed interface AttemptOutcome {
    /** The attempt produced evidence; [result] points at it. */
    @Serializable
    @SerialName("succeeded")
    public data class Succeeded(val result: ResultRef) : AttemptOutcome

    /** The attempt produced nothing usable. */
    @Serializable
    @SerialName("failed")
    public data class Failed(val failure: AttemptFailure) : AttemptOutcome
}

/** A closed attempt with its outcome and the time it was closed. */
@Serializable
public data class AttemptRecord(val attempt: ProcessAttempt, val outcome: AttemptOutcome, val finishedAt: Instant)

/** Terminal verdict of a whole process. */
@Serializable
public enum class ProcessEnd {
    /** The process reached its goal. */
    @SerialName("completed")
    Completed,

    /** The process gave up. */
    @SerialName("failed")
    Failed,

    /** The process was stopped from outside. */
    @SerialName("cancelled")
    Cancelled,
}

/**
 * What a process is doing right now. The cases are exclusive on purpose: an executor is attached only while the
 * process runs, and a waiting process owns no executor, so a wait can never keep a live agent.
 */
@Serializable
public sealed interface ProcessPhase {
    /** Between attempts and waits; the harness decides what happens next. */
    @Serializable
    @SerialName("idle")
    public data object Idle : ProcessPhase

    /** [attempt] is in flight and its executor is attached. */
    @Serializable
    @SerialName("running")
    public data class Running(val attempt: ProcessAttempt) : ProcessPhase

    /** The process is parked on [wait]; no executor is attached. */
    @Serializable
    @SerialName("waiting")
    public data class Waiting(val wait: ProcessWait) : ProcessPhase

    /**
     * Terminal: no new work begins. Only what can still arrive late is answered: a replay of a closed attempt or of a
     * finished wait, the same verdict, and the settlement of external writes (see `ProcessCommand`).
     */
    @Serializable
    @SerialName("ended")
    public data class Ended(val end: ProcessEnd, val at: Instant) : ProcessPhase
}

/**
 * Durable state of one development process, stored as a single record. It is a plain
 * value: every change is a [reduce] step that returns the next record, so a crash between two steps leaves either
 * the old or the new record, never a mix.
 *
 * - [inputRevision] counts revisions of the process input (for example an edited issue or plan); it only grows.
 * - [subject] is the git object currently under work. A result is current while the revision it was produced for
 *   is still the latest and, if it was bound to a subject, that subject is still the same ([isCurrent]).
 * - [history] keeps the latest closed attempts, oldest first. When it overflows, the oldest record is dropped, but
 *   never the last record overall (it defines [stage]) nor the latest success of any stage (the evidence).
 * - [nextAttempt] and [nextWait] are the numbers the next attempt and the next wait get; numbers are never reused.
 * - [nextWriteTry] is the number the next begun try of an external write gets: one more than the highest try in the
 *   journal. Numbers are never reused, so a decision about an old try can never be mistaken for a newer one, even
 *   for a key that the journal forgot in between: forgetting only happens to make room for a new try, which always
 *   carries the highest number.
 * - [grants] are the external authorities the process was given explicitly; [writes] journal what it did to external
 *   systems. An intent is recorded before the call, so a restart finds every write whose outcome is still open
 *   ([unresolvedWrites]) and reconciles it instead of repeating it.
 *
 * Fields added later must have defaults so that stored records stay readable.
 */
@Serializable
public data class ProcessRecord(
    val id: ProcessId,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val inputRevision: Long = 0,
    val subject: SubjectSha? = null,
    val phase: ProcessPhase = ProcessPhase.Idle,
    val history: List<AttemptRecord> = emptyList(),
    val nextAttempt: Int = 1,
    val nextWait: Int = 1,
    val grants: Set<ProcessAuthority> = emptySet(),
    val writes: List<ExternalWrite> = emptyList(),
) {
    init {
        require(inputRevision >= 0) { "Invalid input revision" }
        require(nextAttempt >= 1) { "Invalid next attempt" }
        require(updatedAt >= createdAt) { "Process updated before it was created" }
        require(history.size <= ProcessLimits.HISTORY) { "Too many retained attempts" }
        val numbers = history.map { it.attempt.number }
        require(numbers.zipWithNext().all { (older, newer) -> older < newer }) { "Attempt numbers must grow" }
        val inFlight = (phase as? ProcessPhase.Running)?.attempt
        val lastClosed = numbers.lastOrNull() ?: 0
        if (inFlight == null) {
            require(lastClosed < nextAttempt) { "Attempt number from the future" }
        } else {
            require(inFlight.number == nextAttempt - 1 && lastClosed < inFlight.number) { "Inconsistent attempt" }
            require(inFlight.inputRevision <= inputRevision) { "Attempt from a future input revision" }
        }
        require(history.all { it.attempt.inputRevision <= inputRevision }) { "Attempt from a future input revision" }
        require(stages().size <= ProcessLimits.STAGES) { "Too many stages" }
        require(nextWait >= 1) { "Invalid next wait" }
        val waiting = (phase as? ProcessPhase.Waiting)?.wait
        require(waiting == null || waiting.number == nextWait - 1) { "Inconsistent wait" }
        require(writes.size <= ProcessLimits.WRITES) { "Too many journaled writes" }
        require(writes.map { it.key }.toSet().size == writes.size) { "Duplicate write key" }
    }

    /** The number the next begun try of an external write gets; see the class description. */
    public val nextWriteTry: Int get() = (writes.maxOfOrNull { it.tries } ?: 0) + 1

    /** The attempt whose executor is attached, if any. */
    public val inFlight: ProcessAttempt? get() = (phase as? ProcessPhase.Running)?.attempt

    /** Stage of the most recent attempt, or null before the first one. Derived, so it cannot disagree with it. */
    public val stage: StageName? get() = inFlight?.stage ?: history.lastOrNull()?.attempt?.stage

    /** Whether an attempt started from these inputs would still describe the latest input and subject. */
    public fun isCurrent(attempt: ProcessAttempt): Boolean = attempt.inputRevision == inputRevision &&
        (attempt.subject == null || attempt.subject == subject)

    /** Whether [record] is a success whose inputs are still the latest; failures are never current results. */
    public fun isCurrent(record: AttemptRecord): Boolean =
        record.outcome is AttemptOutcome.Succeeded && isCurrent(record.attempt)

    /** The latest success of [stage], current or not, as audit evidence. */
    public fun latestSuccess(stage: StageName): AttemptRecord? =
        history.lastOrNull { it.attempt.stage == stage && it.outcome is AttemptOutcome.Succeeded }

    /**
     * The result of [stage] if its latest success is still current, otherwise null: a changed input revision or a
     * changed subject makes older results stale without touching them.
     */
    public fun currentResult(stage: StageName): ResultRef? = latestSuccess(stage)
        ?.takeIf { isCurrent(it) }
        ?.let { (it.outcome as AttemptOutcome.Succeeded).result }

    /** The journaled write [key], if any. */
    public fun write(key: WriteKey): ExternalWrite? = writes.firstOrNull { it.key == key }

    /** Writes whose outcome is open, oldest first: after a restart each must be reconciled, never repeated. */
    public fun unresolvedWrites(): List<ExternalWrite> = writes.filter { it.isUnresolved }

    internal fun stages(): Set<StageName> {
        val stages = history.mapTo(LinkedHashSet()) { it.attempt.stage }
        val running = inFlight
        if (running != null) stages += running.stage
        return stages
    }

    override fun toString(): String =
        "ProcessRecord(id=$id, phase=${phase::class.simpleName.orEmpty()}, attempts=${nextAttempt - 1})"
}
