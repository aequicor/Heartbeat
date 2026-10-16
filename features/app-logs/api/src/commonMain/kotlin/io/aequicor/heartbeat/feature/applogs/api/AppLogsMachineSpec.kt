package io.aequicor.heartbeat.feature.applogs.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * Log actions belong to the open feature scope. Closing it cancels the running effect and discards late results.
 * Nothing persists across process restarts; snapshots belong only to their effect and are released on completion.
 *
 * | From | Intent | Guard | To | Effect |
 * |---|---|---|---|---|
 * | Ready | Execute | — | Working | Execute requested operation |
 * | Ready | Retry | last result is Failure | Working | Execute previous operation |
 * | Working | Finished | — | Ready with result | — |
 * | Working | Execute or Retry | — | ignored | — |
 *
 * Effect failures finish with Failure; cancelled save dialogs finish with Cancelled and permit a new operation.
 */
public val AppLogsMachineSpec: MachineSpec<AppLogsState, AppLogsIntent, AppLogsEffect, AppLogsOutput> =
    machineSpec(AppLogsMachineKey, AppLogsState.Ready()) {
        state<AppLogsState.Ready> {
            on<AppLogsIntent.Public.Execute> {
                goto<AppLogsState.Working> { AppLogsState.Working(intent.operation) }
                effect { AppLogsEffect.Execute(intent.operation) }
            }
            on<AppLogsIntent.Public.Retry>(guard = {
                state.outcome == AppLogsOutcome.Failure && state.operation != null
            }) {
                goto<AppLogsState.Working> { AppLogsState.Working(requireNotNull(state.operation)) }
                effect { AppLogsEffect.Execute(requireNotNull(state.operation)) }
            }
        }
        state<AppLogsState.Working> {
            on<AppLogsIntent.Internal.Finished> {
                goto<AppLogsState.Ready> { AppLogsState.Ready(state.operation, intent.outcome) }
            }
        }
        onEffectFailure { _, _ -> AppLogsIntent.Internal.Finished(AppLogsOutcome.Failure) }
    }
