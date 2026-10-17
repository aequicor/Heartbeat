package io.aequicor.heartbeat.feature.harness.api.process

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

internal val processStart: Instant = Instant.fromEpochSeconds(1_000)

internal fun processAt(seconds: Long): Instant = Instant.fromEpochSeconds(1_000 + seconds)

internal fun sha(digit: Char): SubjectSha = SubjectSha(digit.toString().repeat(40))

internal val planStage = StageName("plan")
internal val buildStage = StageName("build")
internal val verifyStage = StageName("verify")

internal val helperExecutor = ExecutorRef(ExecutorKind.Helper, "helper-1")

internal fun evidence(id: String): ResultRef = ResultRef("report", id)

internal fun succeeded(id: String): AttemptOutcome = AttemptOutcome.Succeeded(evidence(id))

internal fun failed(failure: AttemptFailure = AttemptFailure.Error): AttemptOutcome = AttemptOutcome.Failed(failure)

internal fun newProcess(): ProcessRecord = ProcessRecord(ProcessId("p1"), processStart)

/** Applies [command] and requires that it changed the record. */
internal fun ProcessRecord.change(command: ProcessCommand, seconds: Long = 1): ProcessRecord {
    val reduction = reduce(command, processAt(seconds))
    return assertIs<ProcessReduction.Changed>(reduction, "$command gave $reduction").record
}

/** Applies [command] and requires that it was refused for [reason]. */
internal fun ProcessRecord.expectRejected(command: ProcessCommand, reason: ProcessRejection, seconds: Long = 1) {
    assertEquals(ProcessReduction.Rejected(reason), reduce(command, processAt(seconds)))
}

/** Applies [command] and requires that it was recognized as a replay of something already recorded. */
internal fun ProcessRecord.expectUnchanged(command: ProcessCommand, seconds: Long = 1) {
    assertEquals(ProcessReduction.Unchanged(this), reduce(command, processAt(seconds)))
}

/** Runs one whole attempt: start, then finish with [outcome]. */
internal fun ProcessRecord.attempt(
    stage: StageName,
    outcome: AttemptOutcome,
    executor: ExecutorRef = helperExecutor,
    seconds: Long = 1,
): ProcessRecord {
    val started = change(ProcessCommand.Start(stage, executor), seconds)
    return started.change(ProcessCommand.Finish(started.nextAttempt - 1, outcome), seconds + 1)
}
