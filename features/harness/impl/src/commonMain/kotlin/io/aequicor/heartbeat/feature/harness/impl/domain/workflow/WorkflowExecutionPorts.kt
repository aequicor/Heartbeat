package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.workflow.AgentOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOutcome
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

/**
 * One claimed driver generation. Writes serialize branch merges and finish durably before returning. Local
 * work always has Prepared saved first. Infrastructure/ownership failures throw WorkflowExecutionUnavailable,
 * not a memoized author failure. A rejected stale generation cannot continue with a fresh empty journal.
 */
internal interface WorkflowStepJournal {
    val steps: List<WorkflowStep>
    suspend fun prepare(key: StepKey, digest: String): WorkflowStep
    suspend fun complete(key: StepKey, result: JsonElement)
    suspend fun fail(key: StepKey, reason: WorkflowFailure)
}

/**
 * Executes or reconciles one helper step. Saves helper identity/request before send and saves its exact terminal
 * value/failure before returning. Capacity waits are cancellable and never recorded as step failures. Unknown
 * native acceptance must be reconciled before any recovery prompt. The engine never creates a blank local
 * Prepared record for this port: the helper owner journals its acquired identity first.
 */
internal fun interface WorkflowAgentSteps {
    suspend fun execute(key: StepKey, digest: String, prompt: String, options: AgentOptions): String
}

/**
 * Executes or reconciles one organism step. The owner journals the organism's identity before conceiving it, checks
 * the run's live admission and the organism quotas before every side effect and saves the exact terminal outcome
 * (see [toJournal]) before returning. A refusal is a returned [OrganismOutcome.Refused], never an exception. Quota
 * waits are cancellable and never recorded as failures; cancelling the call stops the organism and keeps its quota
 * until its work has actually ended. A restored step must be reconciled with the organism it already owns instead of
 * conceiving another one. The engine never creates a blank local Prepared record for this port.
 */
internal fun interface WorkflowOrganismSteps {
    suspend fun execute(key: StepKey, digest: String, goal: String, options: OrganismOptions): OrganismOutcome
}

/** Stops the current replay without turning an uncertain host/storage operation into a saved author error. */
internal class WorkflowExecutionUnavailable : CancellationException("Workflow execution is unavailable")
