package io.aequicor.heartbeat.feature.effortconfiguration.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget

/**
 * Holds reasoning effort choices of the profile and persists every change.
 *
 * | From | Intent | Guard | To | Effect / output |
 * |---|---|---|---|---|
 * | Idle | Start | | Loading | Load |
 * | Loading | Loaded | | Ready(choices) | |
 * | Loading | LoadFailed | | Ready() | |
 * | Ready | Select | effort differs from stored | Ready(updated) | Save(updated) |
 * | Ready | SaveFailed | | Ready | SaveFailed |
 * | Idle / Loading | Select | — | ignored | selection is not known before loading |
 *
 * Effect failures: Load → LoadFailed, Save → SaveFailed.
 */
public val EffortConfigurationMachineSpec: EffortSpec =
    machineSpec(EffortConfigurationMachineKey, EffortConfigurationState.Idle) {
        state<EffortConfigurationState.Idle> {
            on<EffortConfigurationIntent.Public.Start> {
                goto<EffortConfigurationState.Loading> { EffortConfigurationState.Loading }
                effect { EffortConfigurationEffect.Load }
            }
        }
        state<EffortConfigurationState.Loading> {
            on<EffortConfigurationIntent.Internal.Loaded> {
                goto<EffortConfigurationState.Ready> {
                    EffortConfigurationState.Ready(intent.choices.distinctBy { it.target })
                }
            }
            on<EffortConfigurationIntent.Internal.LoadFailed> {
                goto<EffortConfigurationState.Ready> { EffortConfigurationState.Ready() }
            }
        }
        state<EffortConfigurationState.Ready> {
            on<EffortConfigurationIntent.Public.Select>(
                guard = { state.effortFor(intent.target) != intent.effort },
            ) {
                stay { EffortConfigurationState.Ready(state.choices.with(intent.target, intent.effort)) }
                effect { EffortConfigurationEffect.Save(state.choices.with(intent.target, intent.effort)) }
            }
            on<EffortConfigurationIntent.Internal.SaveFailed> {
                stay { state }
                output { EffortConfigurationOutput.SaveFailed }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                EffortConfigurationEffect.Load -> EffortConfigurationIntent.Internal.LoadFailed
                is EffortConfigurationEffect.Save -> EffortConfigurationIntent.Internal.SaveFailed
            }
        }
    }

private typealias EffortSpec = MachineSpec<
    EffortConfigurationState,
    EffortConfigurationIntent,
    EffortConfigurationEffect,
    EffortConfigurationOutput,
>

private fun List<EffortChoice>.with(target: EngineTarget, effort: String?): List<EffortChoice> =
    filterNot { it.target == target } + listOfNotNull(effort?.let { EffortChoice(target, it) })
