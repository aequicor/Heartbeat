package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * Profile workflow admission and durable driver projection. Ready transitions stay: one run finishing must
 * not cancel another run's effect. Drive writes the journal before submissions; StopRun writes cancellation
 * before cleanup. Neither failed IO nor a timeout proves a helper stopped. The effect handler owns retry of
 * unresolved cleanup; repeated Cancel also retries it. Drive resumes cancellation records through StopRun.
 * Native subscriptions, owned jobs and generation checks belong to the driver, never this pure specification.
 *
 * | From | Intent | Guard | To | Effect / Output |
 * |---|---|---|---|---|
 * | Idle/Failed | Internal.Start | any | Loading, preserve suspension | Restore |
 * | Idle/Loading/Failed | Public | any | stay | Rejected(Unavailable) |
 * | Loading | Restored | unique journal ids | Ready, retain, advance generations | Drive unless suspended |
 * | Loading | Restored | duplicate journal ids | Failed, preserve suspension | none |
 * | Loading | RestoreFailed | any | Failed, preserve suspension | none |
 * | Idle/Loading/Failed | Suspended/Resumed | any | stay, update suspension | none |
 * | Ready | Start | disabled/duplicate/full/invalid | stay | Rejected |
 * | Ready | Start | admitted | stay, append | Drive / Started |
 * | Ready | Cancel | missing/foreign/terminal | stay | Rejected |
 * | Ready | Cancel | owned running | stay, cancellation + generation fence | StopRun / CancellationRequested |
 * | Ready | StepStarted | current driver, valid preparation/progress/recovery | stay, replace step | none |
 * | Ready | StepFinished/StepFailed | current driver, immutable correlation | stay, memoize terminal step | none |
 * | Ready | PermissionsChanged | current executing driver | stay, transient permissions | none |
 * | Ready | Recovered | next generation and attempt, previous driver revoked | stay, fence generation | Drive |
 * | Ready | Finished/Failed | current proof, matching cancellation | stay, terminal + retention | RunFinished |
 * | Ready | TerminalRestored | current projection, same invocation, terminal record | stay, restore it | RunFinished |
 * | Ready | Suspended | enabled | stay, fence generations | Pause running drivers |
 * | Ready | Resumed | suspended | stay, advance generations | Drive running records |
 * | Any | stale/duplicate internal feedback or duplicate suspension | unmatched | ignored | none |
 *
 * Journal loading rejects duplicate ids instead of accepting an ambiguous owner. Retention keeps all Running
 * records, and the newest 20 terminal records per harness within 30 days. UI and tools apply [isVisibleTo].
 */
public val HarnessRunsMachineSpec: MachineSpec<
    HarnessRunsState,
    HarnessRunsIntent,
    HarnessRunsEffect,
    HarnessRunsOutput,
> = machineSpec(HarnessRunsMachineKey, HarnessRunsState.Idle()) {
    state<HarnessRunsState.Idle> {
        on<HarnessRunsIntent.Internal.Start> {
            goto<HarnessRunsState.Loading> { HarnessRunsState.Loading(state.isSuspended) }
            effect { HarnessRunsEffect.Restore }
        }
        on<HarnessRunsIntent.Internal.Suspended>(guard = { !state.isSuspended }) {
            stay { state.copy(isSuspended = true) }
        }
        on<HarnessRunsIntent.Internal.Resumed>(guard = { state.isSuspended }) {
            stay { state.copy(isSuspended = false) }
        }
    }
    state<HarnessRunsState.Failed> {
        on<HarnessRunsIntent.Internal.Start> {
            goto<HarnessRunsState.Loading> { HarnessRunsState.Loading(state.isSuspended) }
            effect { HarnessRunsEffect.Restore }
        }
        on<HarnessRunsIntent.Internal.Suspended>(guard = { !state.isSuspended }) {
            stay { state.copy(isSuspended = true) }
        }
        on<HarnessRunsIntent.Internal.Resumed>(guard = { state.isSuspended }) {
            stay { state.copy(isSuspended = false) }
        }
    }
    state<HarnessRunsState.Loading> {
        on<HarnessRunsIntent.Internal.Restored>(
            guard = { intent.runs.map { it.id }.distinct().size == intent.runs.size },
        ) {
            goto<HarnessRunsState.Ready> {
                HarnessRunsState.Ready(intent.runs.retained(intent.at).resuming(), state.isSuspended)
            }
            effect {
                if (state.isSuspended) {
                    null
                } else {
                    HarnessRunsEffect.Drive(
                        intent.runs.retained(intent.at).resuming().running(),
                    )
                }
            }
        }
        on<HarnessRunsIntent.Internal.RestoreFailed> {
            goto<HarnessRunsState.Failed> { HarnessRunsState.Failed(state.isSuspended) }
        }
        on<HarnessRunsIntent.Internal.Restored>(
            guard = { intent.runs.map { it.id }.distinct().size != intent.runs.size },
        ) {
            goto<HarnessRunsState.Failed> { HarnessRunsState.Failed(state.isSuspended) }
        }
        on<HarnessRunsIntent.Internal.Suspended>(guard = { !state.isSuspended }) {
            stay { state.copy(isSuspended = true) }
        }
        on<HarnessRunsIntent.Internal.Resumed>(guard = { state.isSuspended }) {
            stay { state.copy(isSuspended = false) }
        }
    }
    state<HarnessRunsState.Ready> {
        on<HarnessRunsIntent.Public.Start> {
            stay { if (state.startRejection(intent) == null) state.copy(runs = state.runs + intent.run) else state }
            effect { if (state.startRejection(intent) == null) HarnessRunsEffect.Drive(listOf(intent.run)) else null }
            output {
                state.startRejection(intent)?.let { HarnessRunsOutput.Rejected(intent.requestId, it) }
                    ?: HarnessRunsOutput.Started(intent.requestId, intent.run.id)
            }
        }
        on<HarnessRunsIntent.Public.Cancel> {
            stay { if (state.cancelRejection(intent) == null) state.replace(state.cancelling(intent.run)) else state }
            effect {
                if (state.cancelRejection(
                        intent,
                    ) == null
                ) {
                    HarnessRunsEffect.StopRun(state.cancelling(intent.run))
                } else {
                    null
                }
            }
            output {
                state.cancelRejection(intent)?.let { HarnessRunsOutput.Rejected(intent.requestId, it) }
                    ?: HarnessRunsOutput.CancellationRequested(intent.requestId, intent.run)
            }
        }
        workflowProgress()
        workflowCompletion()
        on<HarnessRunsIntent.Internal.Suspended>(guard = { !state.isSuspended }) {
            stay { state.copy(runs = state.runs.resuming(), isSuspended = true) }
            effect { HarnessRunsEffect.Pause(state.runs.running()) }
        }
        on<HarnessRunsIntent.Internal.Resumed>(guard = { state.isSuspended }) {
            stay { state.copy(runs = state.runs.resuming(), isSuspended = false) }
            effect { HarnessRunsEffect.Drive(state.runs.resuming().running()) }
        }
    }
    any {
        on<HarnessRunsIntent.Public> {
            output {
                HarnessRunsOutput.Rejected(
                    intent.requestId,
                    WorkflowRejection.Unavailable,
                )
            }
        }
    }
    onEffectFailure { effect, _ ->
        when (effect) {
            HarnessRunsEffect.Restore -> HarnessRunsIntent.Internal.RestoreFailed
            is HarnessRunsEffect.Drive, is HarnessRunsEffect.StopRun, is HarnessRunsEffect.Pause -> null
        }
    }
}
