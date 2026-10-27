package io.aequicor.heartbeat.feature.appupdate.impl.presentation

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.appupdate.api.AppBuildInfo
import io.aequicor.heartbeat.feature.appupdate.api.AppBuildInfoSource
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateMachineKey
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdatePhase
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateState
import io.aequicor.heartbeat.feature.appupdate.api.UnsupportedReason
import io.aequicor.heartbeat.feature.appupdate.api.appUpdateOffer
import io.aequicor.heartbeat.feature.appupdate.impl.di.scope.AppUpdatesScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

/** Where the update stands, as the page words it. */
enum class UpdateStageUi {
    /** The machine has not been asked yet. */
    Idle,

    /** Updates are switched off or this host has no update machine. */
    Disabled,

    /** A development launch or a build without an update source. */
    NotReleaseBuild,

    /** The OS or architecture has no release build. */
    UnsupportedPlatform,
    Checking,
    UpToDate,
    CheckFailed,
    Available,

    /** A newer release exists but this install replaces itself only by hand. */
    ManualInstall,
    Downloading,
    Preparing,
    Restarting,
}

/** The build of this launch as the page shows it. [version] is null for a development launch. */
@Immutable
data class AppBuildUi(val version: String?, val train: String?, val isBeta: Boolean, val bundles: List<BundleUi>)

/** One bundle of the running train. */
@Immutable
data class BundleUi(val id: String, val version: String)

/** Why the last check or installation failed, in the page's words. */
enum class UpdateFailureUi {
    Network,
    RateLimited,
    InvalidRelease,
    Corrupted,
    NotEnoughSpace,
    Storage,
    ManualInstallRequired,
    InstallFailed,
    PreviousAttemptFailed,
}

/** The build of this launch and the state of the update machine; no paths or URLs other than the release page. */
@Immutable
data class AppUpdatesScreenState(
    val build: AppBuildUi? = null,
    val stage: UpdateStageUi = UpdateStageUi.Idle,
    val version: String? = null,
    val percent: Int? = null,
    val failure: UpdateFailureUi? = null,
    val pageUrl: String? = null,
) : MVIState {
    /** Whether a check can be started now. */
    val canCheck: Boolean
        get() = when (stage) {
            UpdateStageUi.Idle,
            UpdateStageUi.UpToDate,
            UpdateStageUi.CheckFailed,
            UpdateStageUi.Available,
            UpdateStageUi.ManualInstall,
            -> true

            else -> false
        }

    /** Whether the offered release can be installed now. */
    val canInstall: Boolean get() = stage == UpdateStageUi.Available
}

/** User requests of the page. */
sealed interface AppUpdatesScreenIntent : MVIIntent {
    /** Looks for a newer release. */
    data object Check : AppUpdatesScreenIntent

    /** Installs the offered release and restarts. */
    data object Install : AppUpdatesScreenIntent
}

/** The page reports everything in its state. */
sealed interface AppUpdatesScreenAction : MVIAction

/** Lifecycle-owned FlowMVI projection of the app-scope update machine and the build of this launch. */
@SingleIn(AppUpdatesScope::class)
@Inject
class AppUpdatesModel(
    machines: MachineRegistry,
    buildInfo: AppBuildInfoSource,
    @ForScope(AppUpdatesScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    private val log = Log.tag("AppUpdates")

    private val screen = factory.createScreen<AppUpdatesScreenState, AppUpdatesScreenIntent, AppUpdatesScreenAction>(
        "AppUpdates",
        AppUpdatesScreenState(),
        onError = { copy(stage = UpdateStageUi.CheckFailed, failure = UpdateFailureUi.InstallFailed) },
    ) {
        whileSubscribed {
            launch { buildInfo.read().toUi().let { build -> updateState { copy(build = build) } } }
            @OptIn(ExperimentalCoroutinesApi::class)
            machines.observe(AppUpdateMachineKey)
                .flatMapLatest { ref -> ref?.state ?: flowOf(null) }
                .map { state -> state.toStage() }
                .distinctUntilChanged()
                .collect { projection ->
                    updateState {
                        copy(
                            stage = projection.stage,
                            version = projection.version,
                            percent = projection.percent,
                            failure = projection.failure,
                            pageUrl = projection.pageUrl,
                        )
                    }
                }
        }
        reduce { intent ->
            val machine = when (intent) {
                AppUpdatesScreenIntent.Check -> AppUpdateIntent.Public.Check
                AppUpdatesScreenIntent.Install -> AppUpdateIntent.Public.Install
            }
            val result = machines.send(AppUpdateMachineKey, machine)
            log.i { "update request $intent result=$result" }
        }
    }

    val store = screen.store

    /** Latest screen state, read synchronously for the first frame. */
    val current = screen.current

    init {
        store.start(scope.coroutineScope)
    }
}

/** The stage fields of [AppUpdatesScreenState] that come from the update machine. */
internal data class UpdateProjection(
    val stage: UpdateStageUi,
    val version: String? = null,
    val percent: Int? = null,
    val failure: UpdateFailureUi? = null,
    val pageUrl: String? = null,
)

internal fun AppBuildInfo.toUi(): AppBuildUi =
    AppBuildUi(version, train, isBeta, bundles.map { BundleUi(it.id, it.version) })

internal fun AppUpdateFailure.toUi(): UpdateFailureUi = when (this) {
    AppUpdateFailure.Network -> UpdateFailureUi.Network
    AppUpdateFailure.RateLimited -> UpdateFailureUi.RateLimited
    AppUpdateFailure.InvalidRelease -> UpdateFailureUi.InvalidRelease
    AppUpdateFailure.Corrupted -> UpdateFailureUi.Corrupted
    AppUpdateFailure.NotEnoughSpace -> UpdateFailureUi.NotEnoughSpace
    AppUpdateFailure.Storage -> UpdateFailureUi.Storage
    AppUpdateFailure.ManualInstallRequired -> UpdateFailureUi.ManualInstallRequired
    AppUpdateFailure.InstallFailed -> UpdateFailureUi.InstallFailed
    AppUpdateFailure.PreviousAttemptFailed -> UpdateFailureUi.PreviousAttemptFailed
}

/** The page's view of [this] machine state; null is a host without the machine. */
internal fun AppUpdateState?.toStage(): UpdateProjection = when (this) {
    null -> UpdateProjection(UpdateStageUi.Disabled)

    AppUpdateState.Inactive -> UpdateProjection(UpdateStageUi.Idle)

    is AppUpdateState.Unsupported -> UpdateProjection(
        when (reason) {
            UnsupportedReason.NotReleaseBuild -> UpdateStageUi.NotReleaseBuild
            UnsupportedReason.UnsupportedPlatform -> UpdateStageUi.UnsupportedPlatform
        },
    )

    AppUpdateState.UpToDate -> UpdateProjection(UpdateStageUi.UpToDate)

    is AppUpdateState.CheckFailed -> UpdateProjection(UpdateStageUi.CheckFailed, failure = failure.toUi())

    is AppUpdateState.Checking,
    is AppUpdateState.Available,
    is AppUpdateState.Installing,
    is AppUpdateState.Restarting,
    -> offerProjection()
}

private fun AppUpdateState.offerProjection(): UpdateProjection {
    val offer = appUpdateOffer() ?: return UpdateProjection(UpdateStageUi.Checking)
    val isChecking = this is AppUpdateState.Checking
    val stage = when (offer.phase) {
        AppUpdatePhase.Ready -> when {
            isChecking -> UpdateStageUi.Checking
            offer.failure == AppUpdateFailure.ManualInstallRequired -> UpdateStageUi.ManualInstall
            else -> UpdateStageUi.Available
        }

        is AppUpdatePhase.Downloading -> UpdateStageUi.Downloading

        AppUpdatePhase.Preparing -> UpdateStageUi.Preparing

        AppUpdatePhase.Restarting -> UpdateStageUi.Restarting
    }
    val percent = (offer.phase as? AppUpdatePhase.Downloading)?.percent
    return UpdateProjection(stage, offer.version, percent, offer.failure?.toUi(), offer.pageUrl)
}
