package io.aequicor.heartbeat.feature.appupdate.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent.Internal
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent.Public

/**
 * Finds a newer release and installs it on the user's request. Checks run in the background; only [Public.Install]
 * downloads anything, and once the installer is started the machine stays [AppUpdateState.Restarting] until the
 * host quits the app.
 *
 * | From | Intent | Guard | To | Effect |
 * |---|---|---|---|---|
 * | Inactive, UpToDate, CheckFailed | Check | | Checking | FetchLatest |
 * | Available | Check | | Checking (offered = release) | FetchLatest |
 * | Checking | Internal.Unsupported | | Unsupported | |
 * | Checking | Checked | release found | Available (release, failure) | |
 * | Checking | Checked | no newer release | UpToDate | |
 * | Checking | CheckFailed | a release is offered | Available (offered) | |
 * | Checking | CheckFailed | nothing offered | CheckFailed | |
 * | Checking | Install | a release is offered | Installing (Downloading) | Install |
 * | Available | Install | | Installing (Downloading) | Install |
 * | Installing | Progress | | Installing (Downloading received of total) | |
 * | Installing | Preparing | | Installing (Preparing) | |
 * | Installing | Launched | | Restarting | |
 * | Installing | InstallFailed | | Available (release, failure) | |
 * | Unsupported, Checking, UpToDate, CheckFailed, Available | Deactivate | | Inactive | |
 *
 * Install is also accepted after a failed attempt: the button retries it. A check that fails while a release is
 * offered keeps the offer. Installing ignores Check and Deactivate, so a started installation always ends in
 * Restarting or back in Available; Restarting accepts nothing.
 *
 * ```mermaid
 * stateDiagram-v2
 *     [*] --> Inactive
 *     Inactive --> Checking: Check
 *     UpToDate --> Checking: Check
 *     CheckFailed --> Checking: Check
 *     Available --> Checking: Check
 *     Checking --> Unsupported: Unsupported
 *     Checking --> Available: Checked (newer) / CheckFailed (offered)
 *     Checking --> UpToDate: Checked (none)
 *     Checking --> CheckFailed: CheckFailed
 *     Checking --> Installing: Install (offered)
 *     Available --> Installing: Install
 *     Installing --> Installing: Progress / Preparing
 *     Installing --> Restarting: Launched
 *     Installing --> Available: InstallFailed
 *     Unsupported --> Inactive: Deactivate
 *     Checking --> Inactive: Deactivate
 *     UpToDate --> Inactive: Deactivate
 *     CheckFailed --> Inactive: Deactivate
 *     Available --> Inactive: Deactivate
 * ```
 */
public val AppUpdateMachineSpec: MachineSpec<AppUpdateState, AppUpdateIntent, AppUpdateEffect, AppUpdateOutput> =
    machineSpec(AppUpdateMachineKey, AppUpdateState.Inactive) {
        state<AppUpdateState.Inactive> {
            on<Public.Check> {
                goto<AppUpdateState.Checking> { AppUpdateState.Checking() }
                effect { AppUpdateEffect.FetchLatest }
            }
        }
        state<AppUpdateState.Unsupported> {
            on<Public.Deactivate> { goto<AppUpdateState.Inactive> { AppUpdateState.Inactive } }
        }
        state<AppUpdateState.Checking> {
            on<Internal.Unsupported> { goto<AppUpdateState.Unsupported> { AppUpdateState.Unsupported(intent.reason) } }
            on<Internal.Checked>(guard = { intent.release != null }) {
                goto<AppUpdateState.Available> {
                    AppUpdateState.Available(checkNotNull(intent.release), intent.failure)
                }
            }
            on<Internal.Checked>(guard = { intent.release == null }) {
                goto<AppUpdateState.UpToDate> { AppUpdateState.UpToDate }
            }
            on<Internal.CheckFailed>(guard = { state.offered != null }) {
                goto<AppUpdateState.Available> { AppUpdateState.Available(checkNotNull(state.offered)) }
            }
            on<Internal.CheckFailed>(guard = { state.offered == null }) {
                goto<AppUpdateState.CheckFailed> { AppUpdateState.CheckFailed(intent.failure) }
            }
            on<Public.Install>(guard = { state.offered != null }) {
                goto<AppUpdateState.Installing> { installing(checkNotNull(state.offered)) }
                effect { AppUpdateEffect.Install(checkNotNull(state.offered)) }
            }
            on<Public.Deactivate> { goto<AppUpdateState.Inactive> { AppUpdateState.Inactive } }
        }
        state<AppUpdateState.UpToDate> {
            on<Public.Check> {
                goto<AppUpdateState.Checking> { AppUpdateState.Checking() }
                effect { AppUpdateEffect.FetchLatest }
            }
            on<Public.Deactivate> { goto<AppUpdateState.Inactive> { AppUpdateState.Inactive } }
        }
        state<AppUpdateState.CheckFailed> {
            on<Public.Check> {
                goto<AppUpdateState.Checking> { AppUpdateState.Checking() }
                effect { AppUpdateEffect.FetchLatest }
            }
            on<Public.Deactivate> { goto<AppUpdateState.Inactive> { AppUpdateState.Inactive } }
        }
        state<AppUpdateState.Available> {
            on<Public.Check> {
                goto<AppUpdateState.Checking> { AppUpdateState.Checking(offered = state.release) }
                effect { AppUpdateEffect.FetchLatest }
            }
            on<Public.Install> {
                goto<AppUpdateState.Installing> { installing(state.release) }
                effect { AppUpdateEffect.Install(state.release) }
            }
            on<Public.Deactivate> { goto<AppUpdateState.Inactive> { AppUpdateState.Inactive } }
        }
        state<AppUpdateState.Installing> {
            // Progress stays in the state: leaving it would cancel the running installation effect.
            on<Internal.Progress> {
                stay { state.copy(progress = InstallProgress.Downloading(intent.received, intent.total)) }
            }
            on<Internal.Preparing> { stay { state.copy(progress = InstallProgress.Preparing) } }
            on<Internal.Launched> { goto<AppUpdateState.Restarting> { AppUpdateState.Restarting(state.release) } }
            on<Internal.InstallFailed> {
                goto<AppUpdateState.Available> { AppUpdateState.Available(state.release, intent.failure) }
            }
        }
        state<AppUpdateState.Restarting>()
        onEffectFailure { effect, _ ->
            when (effect) {
                AppUpdateEffect.FetchLatest -> Internal.CheckFailed(AppUpdateFailure.Network)
                is AppUpdateEffect.Install -> Internal.InstallFailed(AppUpdateFailure.InstallFailed)
            }
        }
    }

private fun installing(release: AppRelease): AppUpdateState.Installing =
    AppUpdateState.Installing(release, InstallProgress.Downloading(total = release.size))
