package io.aequicor.heartbeat.feature.harness.impl.data.run

import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep

/** Full snapshot writes must not discard parallel progress or rewrite memoized values. */
internal fun WorkflowRun.isMonotonicUpdateOf(old: WorkflowRun): Boolean {
    if (attempt < old.attempt || (old.cancellation != null && cancellation != old.cancellation)) return false
    val proposed = steps.associateBy { it.key }
    val previous = old.steps.associateBy { it.key }
    return old.steps.all { step -> proposed[step.key]?.isProgressOf(step) == true } &&
        steps.filter { it.key !in previous }.all { it.attempt == 0 && !it.isTerminal }
}

private fun WorkflowStep.isProgressOf(old: WorkflowStep): Boolean = when {
    this == old -> true
    old.isTerminal || promptSha != old.promptSha -> false
    attempt == old.attempt + 1 -> phase == StepPhase.Prepared && request != null && request != old.request
    attempt != old.attempt || helper != old.helper || request != old.request -> false
    !matchesCapturedNative(old) -> false
    old.phase == StepPhase.Running -> phase != StepPhase.Prepared
    else -> true
}

private fun WorkflowStep.matchesCapturedNative(old: WorkflowStep): Boolean =
    (old.session == null || old.session == session) && (old.turn == null || old.turn == turn)

private val WorkflowStep.isTerminal: Boolean get() = phase == StepPhase.Completed || phase == StepPhase.Failed
