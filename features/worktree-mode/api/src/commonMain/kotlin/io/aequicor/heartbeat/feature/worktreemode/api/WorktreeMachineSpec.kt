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
 *
 * Individual task transitions use pure reducers in this API against the journaled phase. Keeping the
 * machine in Ready prevents one chat's progress from cancelling another chat's accepted IO.
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
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                WorktreeEffect.Load -> WorktreeIntent.Internal.LoadFailed
                WorktreeEffect.ObserveWorkers -> null
                is WorktreeEffect.Apply -> null
            }
        }
    }
