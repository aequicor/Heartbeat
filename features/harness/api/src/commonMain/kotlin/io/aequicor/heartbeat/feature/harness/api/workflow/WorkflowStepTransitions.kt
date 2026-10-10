package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.harness.api.HarnessLimits

internal fun WorkflowRun.accepts(step: WorkflowStep, isTerminal: Boolean): Boolean {
    val old = steps.firstOrNull { it.key == step.key }
    return when {
        old == null -> !isTerminal && step.attempt == 0 && steps.size < HarnessLimits.STEPS
        old.isTerminal || old.promptSha != step.promptSha -> false
        step.attempt == old.attempt + 1 -> !isTerminal && step.isRecoveryOf(old)
        !step.matchesAttempt(old) -> false
        else -> isTerminal || old.phase != StepPhase.Running || step.phase == StepPhase.Running
    }
}

private val WorkflowStep.isTerminal: Boolean get() = phase == StepPhase.Completed || phase == StepPhase.Failed

private fun WorkflowStep.isRecoveryOf(old: WorkflowStep): Boolean =
    phase == StepPhase.Prepared && request != null && request != old.request

private fun WorkflowStep.matchesAttempt(old: WorkflowStep): Boolean {
    val isIdentityMatching = attempt == old.attempt && helper == old.helper && request == old.request
    return isIdentityMatching && matchesNative(old)
}

private fun WorkflowStep.matchesNative(old: WorkflowStep): Boolean {
    val isSessionMatching = old.session == null || old.session == session
    val isTurnMatching = old.turn == null || old.turn == turn
    return isSessionMatching && isTurnMatching
}

internal fun WorkflowRun.withStep(step: WorkflowStep): WorkflowRun = copy(
    steps = if (steps.any { it.key == step.key }) steps.map { if (it.key == step.key) step else it } else steps + step,
)
