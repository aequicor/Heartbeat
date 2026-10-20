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
 * | Loading | Loaded | | Ready(saved or Automatic) | Observe |
 * | Loading | LoadFailed | | LoadError | |
 * | LoadError | Retry | | Loading | Load |
 * | Ready | Observed | | stay with latest level | |
 * | Ready | LoadFailed | | LoadError | |
 * | Ready | SetLevel | different level | Saving(latest, requested) | Observe + Save(requested) |
 * | Saving | Observed | | stay with latest level | |
 * | Saving | Saved | | Ready(latest observed) | Observe |
 * | Saving | SaveFailed | | Ready(latest observed, failed) | Observe |
 *
 * All other intents are ignored, including edits before loading and overlapping writes. Load failure forbids
 * editor writes until Retry succeeds. Execution observes committed profile policy independently of this editor.
 * Observation updates use stay so they cannot cancel writes. Save results never assume the requested mode is
 * still current: another writer may have committed afterwards. Every entry into Ready/Saving subscribes to
 * the current profile snapshot again; stale observations from the preceding state are discarded by the runtime.
 * An observation failure during Saving is logged; saving continues and completion restarts observation.
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
            effect { StudioSecurityEffect.Observe }
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
        on<Internal.Observed> { stay { state.copy(level = intent.level) } }
        on<Internal.LoadFailed> { goto<StudioSecurityState.LoadError> { StudioSecurityState.LoadError } }
        on<Public.SetLevel>(guard = { intent.level != state.level }) {
            goto<StudioSecurityState.Saving> { StudioSecurityState.Saving(state.level, intent.level) }
            effect { StudioSecurityEffect.Observe }
            effect { StudioSecurityEffect.Save(intent.level) }
        }
    }
    state<StudioSecurityState.Saving> {
        on<Internal.Observed> { stay { state.copy(level = intent.level) } }
        on<Internal.Saved> {
            goto<StudioSecurityState.Ready> { StudioSecurityState.Ready(state.level) }
            effect { StudioSecurityEffect.Observe }
        }
        on<Internal.SaveFailed> {
            goto<StudioSecurityState.Ready> { StudioSecurityState.Ready(state.level, isSaveFailed = true) }
            effect { StudioSecurityEffect.Observe }
        }
    }
    onEffectFailure { effect, _ ->
        when (effect) {
            StudioSecurityEffect.Load, StudioSecurityEffect.Observe -> Internal.LoadFailed
            is StudioSecurityEffect.Save -> Internal.SaveFailed
        }
    }
}
