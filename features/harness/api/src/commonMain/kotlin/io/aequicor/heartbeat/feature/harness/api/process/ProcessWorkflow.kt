package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Durable identity of one stage attempt, including the process lifetime. Keep it across retries. */
@Serializable
public data class ProcessWorkflowKey(
    val process: ProcessId,
    val incarnation: String,
    val attempt: Int,
    val stage: StageName,
) {
    init {
        require(isPlainToken(incarnation))
        require(attempt >= 1)
    }

    override fun toString(): String = "ProcessWorkflowKey(***)"
}

/** Immutable admission binding. The digest covers the workflow, input, revision, script and project context. */
@Serializable
public data class ProcessWorkflow(val run: RunId, val script: ItemId, val digest: String) {
    init {
        require(isPlainToken(run.value) && isPlainToken(script.value))
        require(digest.length == WORKFLOW_DIGEST_CHARS && digest.all { it in '0'..'9' || it in 'a'..'f' })
    }

    override fun toString(): String = "ProcessWorkflow(***)"
}

/** Returns the current attempt key for this stage, or the next attempt number without performing IO. */
public fun ProcessRecord.workflowKey(stage: StageName): ProcessWorkflowKey {
    val current = inFlight?.takeIf { it.stage == stage }
    return ProcessWorkflowKey(id, incarnation, current?.number ?: nextAttempt, stage)
}

/** An exact reservation retry keeps its first run identity; conflicting or retired keys never start work. */
internal fun ProcessRecord.reserveWorkflow(command: ProcessCommand.StartWorkflow, now: Instant): ProcessReduction {
    val rejection = reservationRejection(command)
    val existing = workflowAttempt(command.key.attempt)
    return when {
        rejection != null -> rejected(rejection)
        existing != null -> replayWorkflow(existing, command)
        else -> reserveNewWorkflow(command, now)
    }
}

private fun ProcessRecord.reservationRejection(command: ProcessCommand.StartWorkflow): ProcessRejection? = when {
    command.key.process != id || command.key.incarnation != incarnation -> ProcessRejection.UnknownAttempt

    definition != command.definition -> ProcessRejection.DefinitionMismatch

    definition != null && definition.script != command.workflow.script -> ProcessRejection.OutcomeConflict

    command.inputRevision != null &&
        (command.inputRevision != inputRevision || command.subject != subject) -> ProcessRejection.StaleRevision

    else -> null
}

private fun ProcessRecord.replayWorkflow(
    existing: ProcessAttempt,
    command: ProcessCommand.StartWorkflow,
): ProcessReduction {
    val workflow = existing.workflow
    return if (existing.stage == command.key.stage && workflow?.script == command.workflow.script &&
        workflow.digest == command.workflow.digest
    ) {
        // The caller returns the durable record; a concurrent proposal's generated RunId is deliberately ignored.
        ProcessReduction.Unchanged(this)
    } else {
        rejected(ProcessRejection.OutcomeConflict)
    }
}

private fun ProcessRecord.reserveNewWorkflow(command: ProcessCommand.StartWorkflow, now: Instant): ProcessReduction {
    val key = command.key
    return when {
        key.attempt != nextAttempt -> rejected(ProcessRejection.UnknownAttempt)

        phase is ProcessPhase.Ended -> rejected(ProcessRejection.Closed)

        phase is ProcessPhase.Running -> rejected(ProcessRejection.AttemptInFlight)

        phase is ProcessPhase.Waiting -> rejected(ProcessRejection.WaitPending)

        key.stage !in stages() && stages().size >= ProcessLimits.STAGES -> rejected(ProcessRejection.TooManyStages)

        else -> {
            val attempt = ProcessAttempt(
                key.attempt,
                key.stage,
                ExecutorRef(ExecutorKind.Workflow, command.workflow.run.value),
                inputRevision,
                subject,
                now,
                command.workflow,
            )
            changed(copy(phase = ProcessPhase.Running(attempt), nextAttempt = nextAttempt + 1, updatedAt = now))
        }
    }
}

private fun ProcessRecord.workflowAttempt(number: Int): ProcessAttempt? = inFlight?.takeIf { it.number == number }
    ?: history.firstOrNull { it.attempt.number == number }?.attempt

private const val WORKFLOW_DIGEST_CHARS = 64

/** A delayed cancellation can never finish an attempt of a reopened process or a replacement executor. */
internal fun ProcessRecord.cancelWorkflow(command: ProcessCommand.CancelWorkflow, now: Instant): ProcessReduction {
    val key = command.key
    if (key.process != id || key.incarnation != incarnation) return rejected(ProcessRejection.UnknownAttempt)
    val attempt = inFlight?.takeIf { it.number == key.attempt }
        ?: history.firstOrNull { it.attempt.number == key.attempt }?.attempt
    return if (attempt?.stage != key.stage || attempt.workflow != command.workflow) {
        rejected(ProcessRejection.OutcomeConflict)
    } else {
        reduce(ProcessCommand.Finish(key.attempt, AttemptOutcome.Failed(AttemptFailure.Cancelled)), now)
    }
}
