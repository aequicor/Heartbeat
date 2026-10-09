package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.core.statemachine.StateBuilder

internal typealias RunsTransitions = StateBuilder<
    HarnessRunsState,
    HarnessRunsState.Ready,
    HarnessRunsIntent,
    HarnessRunsEffect,
    HarnessRunsOutput,
>

internal fun RunsTransitions.workflowProgress() {
    on<HarnessRunsIntent.Internal.StepStarted>(guard = {
        (intent.step.phase == StepPhase.Prepared || intent.step.phase == StepPhase.Running) &&
            state.executing(intent.run, intent.generation)?.accepts(intent.step, isTerminal = false) == true
    }) {
        stay { state.replace(checkNotNull(state.current(intent.run, intent.generation)).withStep(intent.step)) }
    }
    on<HarnessRunsIntent.Internal.StepFinished>(guard = {
        intent.step.phase == StepPhase.Completed &&
            state.executing(intent.run, intent.generation)?.accepts(intent.step, isTerminal = true) == true
    }) {
        stay { state.replace(checkNotNull(state.current(intent.run, intent.generation)).withStep(intent.step)) }
    }
    on<HarnessRunsIntent.Internal.StepFailed>(guard = {
        intent.step.phase == StepPhase.Failed &&
            state.executing(intent.run, intent.generation)?.accepts(intent.step, isTerminal = true) == true
    }) {
        stay { state.replace(checkNotNull(state.current(intent.run, intent.generation)).withStep(intent.step)) }
    }
    on<HarnessRunsIntent.Internal.PermissionsChanged>(guard = {
        state.executing(intent.run, intent.generation) != null
    }) {
        stay {
            state.replace(
                checkNotNull(state.current(intent.run, intent.generation)).copy(awaiting = intent.awaiting),
            )
        }
    }
    on<HarnessRunsIntent.Internal.Recovered>(guard = {
        intent.generation > 0 && state.executing(intent.run, intent.generation - 1)?.let {
            intent.attempt == it.attempt + 1
        } == true
    }) {
        stay {
            state.replace(
                checkNotNull(state.current(intent.run, intent.generation - 1)).copy(
                    attempt = intent.attempt,
                    driverGeneration = intent.generation,
                    awaiting = emptyMap(),
                ),
            )
        }
        effect {
            HarnessRunsEffect.Drive(
                listOf(
                    checkNotNull(state.current(intent.run, intent.generation - 1)).copy(
                        attempt = intent.attempt,
                        driverGeneration = intent.generation,
                        awaiting = emptyMap(),
                    ),
                ),
            )
        }
    }
}

internal fun RunsTransitions.workflowCompletion() {
    on<HarnessRunsIntent.Internal.TerminalRestored>(guard = {
        intent.run.status != WorkflowStatus.Running && intent.run.driverGeneration <= intent.generation &&
            state.current(intent.run.id, intent.generation)?.sameInvocation(intent.run) == true
    }) {
        stay {
            state.copy(
                runs = state.runs.map {
                    if (it.id == intent.run.id) intent.run.copy(awaiting = emptyMap()) else it
                }.retained(checkNotNull(intent.run.finishedAt)),
            )
        }
        output { HarnessRunsOutput.RunFinished(intent.run.id, intent.run.status) }
    }
    on<HarnessRunsIntent.Internal.Finished>(guard = {
        state.executing(intent.run, intent.generation)?.let { intent.at >= it.startedAt } == true
    }) {
        stay { state.terminal(intent.run, intent.status, intent.at) }
        output { HarnessRunsOutput.RunFinished(intent.run, intent.status) }
    }
    on<HarnessRunsIntent.Internal.Failed>(guard = {
        state.current(intent.run, intent.generation)?.let {
            intent.at >= it.startedAt && (it.cancellation == null || it.cancellation == intent.reason)
        } == true
    }) {
        stay { state.terminal(intent.run, WorkflowStatus.Failed(intent.reason), intent.at) }
        output { HarnessRunsOutput.RunFinished(intent.run, WorkflowStatus.Failed(intent.reason)) }
    }
}

private fun WorkflowRun.sameInvocation(other: WorkflowRun): Boolean =
    id == other.id && harness == other.harness && workflow == other.workflow && pinned == other.pinned &&
        input == other.input && caller == other.caller && origin == other.origin && startedAt == other.startedAt &&
        deadline == other.deadline && wake == other.wake && routing == other.routing
