package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityIntent.Internal
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityIntent.Public

/**
 * Profile security settings. Writes belong to the profile and update execution policy only after success.
 * The machine is not persisted: every new profile lifetime reads the durable setting again.
 *
 * | From | Intent | Guard | To | Effect |
 * |---|---|---|---|---|
 * | Idle | Start | | Loading | Load |
 * | Loading | Loaded | | Ready(saved or Automatic) | |
 * | Loading | LoadFailed | | LoadError | |
 * | LoadError | Retry | | Loading | Load |
 * | Ready | SetLevel | different level | Saving(previous, requested) | Save(requested) |
 * | Saving | Saved | | Ready(requested) | |
 * | Saving | SaveFailed | | Ready(previous, failed) | |
 *
 * All other intents are ignored, including edits before loading and overlapping writes. Load failure forbids
 * editor writes until Retry succeeds. Execution observes committed profile policy independently of this editor.
 * An effect result that exits its state is its last operation; profile closure cancels effects,
 * while settings screen closure does not.
 */
public val StudioSecurityMachineSpec: MachineSpec<
    StudioSecurityState,
    StudioSecurityIntent,
    StudioSecurityEffect,
    StudioSecurityOutput,
> = machineSpec(StudioSecurityMachineKey, StudioSecurityState.Idle) {
    state<StudioSecurityState.Idle> {
        on<Public.Start> {
            goto<StudioSecurityState.Loading> { StudioSecurityState.Loading }
            effect { StudioSecurityEffect.Load }
        }
    }
    state<StudioSecurityState.Loading> {
        on<Internal.Loaded> {
            goto<StudioSecurityState.Ready> {
                StudioSecurityState.Ready(intent.level ?: StudioSecurityLevel.Automatic)
            }
        }
        on<Internal.LoadFailed> { goto<StudioSecurityState.LoadError> { StudioSecurityState.LoadError } }
    }
    state<StudioSecurityState.LoadError> {
        on<Public.Retry> {
            goto<StudioSecurityState.Loading> { StudioSecurityState.Loading }
            effect { StudioSecurityEffect.Load }
        }
    }
    state<StudioSecurityState.Ready> {
        on<Public.SetLevel>(guard = { intent.level != state.level }) {
            goto<StudioSecurityState.Saving> { StudioSecurityState.Saving(state.level, intent.level) }
            effect { StudioSecurityEffect.Save(intent.level) }
        }
    }
    state<StudioSecurityState.Saving> {
        on<Internal.Saved> {
            goto<StudioSecurityState.Ready> { StudioSecurityState.Ready(state.requested) }
        }
        on<Internal.SaveFailed> {
            goto<StudioSecurityState.Ready> { StudioSecurityState.Ready(state.level, isSaveFailed = true) }
        }
    }
    onEffectFailure { effect, _ ->
        when (effect) {
            StudioSecurityEffect.Load -> Internal.LoadFailed
            is StudioSecurityEffect.Save -> Internal.SaveFailed
        }
    }
}
