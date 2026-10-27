package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * Profile worktree workflow. External commands execute through durable effects; stale task revisions are ignored.
 *
 * | From | Intent | To | Effect |
 * |---|---|---|---|
 * | Idle | Start | Loading | Load |
 * | Loading | Loaded / LoadFailed | Ready / LoadError | |
 * | LoadError | RetryLoad | Loading | Load |
 * | Ready | Public command | stay | Apply |
 * | Ready | Updated (newer revision) | stay | Changed output |
 * | Ready | Updated (same or older revision) | stay, unchanged | |
 *
 * Individual task transitions use pure reducers in this API against the journaled phase. Keeping the
 * machine in Ready prevents one chat's progress from cancelling another chat's accepted IO.
 *
 * | Task phase | Intent | Guard | Result |
 * |---|---|---|---|
 * | Working / ActionWorking | RunSubmissionObservationLost | Matching pending identity; no turn | RecoveryRequired |
 * | Working / ActionWorking | RunRejected | Unsubmitted request | Failed; clear run; save receipt |
 * | RecoveryRequired | RunSubmissionReconciled | Pending identity | Failed; clear ownership; save receipt |
 * | RecoveryRequired | RunAdopted | Pending Coding run with exactly this recorded session and turn | Working |
 * | Any | Either submission command | Accepted, settled or mismatched identity | Unchanged |
 * | AwaitingDecision | ChooseAction(HandOff) | Sandbox root (`canHandOff`) | Unchanged; journal publishes branch |
 * | AwaitingDecision | Branch published (`handedOff`) | Sandbox root, same run as at the choice | Retained |
 * | Any | ChooseAction(HandOff) | Plain worktree, child sandbox or another phase | Unchanged |
 *
 * Lost submission observation retains the lease and never proves rejection or native completion. Recovery needs
 * the same session and either confirmed terminal identity or fresh native idle inspection before releasing ownership.
 * Rejection requires no recorded session or turn and no recovery barrier.
 * A closed-submission proof survives restart and is cleared before any new run; it never fabricates a native outcome.
 * A restarted host that finds the recorded native turn still running adopts it instead: ownership never changes hands.
 */
public val WorktreeMachineSpec: MachineSpec<WorktreeState, WorktreeIntent, WorktreeEffect, WorktreeOutput> =
    machineSpec(WorktreeMachineKey, WorktreeState.Idle) {
        state<WorktreeState.Idle> {
            on<WorktreeIntent.Public.Start> {
                goto<WorktreeState.Loading> { WorktreeState.Loading }
                effect { WorktreeEffect.Load }
            }
        }
        state<WorktreeState.Loading> {
            on<WorktreeIntent.Internal.Loaded> {
                goto<WorktreeState.Ready> { WorktreeState.Ready(intent.tasks) }
                effect { WorktreeEffect.ObserveWorkers }
            }
            on<WorktreeIntent.Internal.LoadFailed> { goto<WorktreeState.LoadError> { WorktreeState.LoadError } }
        }
        state<WorktreeState.LoadError> {
            on<WorktreeIntent.Public.RetryLoad> {
                goto<WorktreeState.Loading> { WorktreeState.Loading }
                effect { WorktreeEffect.Load }
            }
        }
        state<WorktreeState.Ready> {
            on<WorktreeIntent.Public>(guard = {
                intent != WorktreeIntent.Public.Start && intent != WorktreeIntent.Public.RetryLoad
            }) { effect { WorktreeEffect.Apply(intent) } }
            on<WorktreeIntent.Internal.Updated>(guard = {
                intent.task.revision > (state.tasks[intent.task.chatId]?.revision ?: -1)
            }) {
                stay { WorktreeState.Ready(state.tasks + (intent.task.chatId to intent.task)) }
                output { WorktreeOutput.Changed(intent.task.chatId) }
            }
            // A task result reaches the machine from both the command that made it and the worker observation.
            on<WorktreeIntent.Internal.Updated>(guard = {
                intent.task.revision <= (state.tasks[intent.task.chatId]?.revision ?: -1)
            }) { stay { state } }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                WorktreeEffect.Load -> WorktreeIntent.Internal.LoadFailed
                WorktreeEffect.ObserveWorkers -> null
                is WorktreeEffect.Apply -> null
            }
        }
    }
