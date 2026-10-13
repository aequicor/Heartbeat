package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateMachineKey
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateOffer
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdatePhase
import io.aequicor.heartbeat.feature.appupdate.api.appUpdateOffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import pro.respawn.flowmvi.api.PipelineContext

/**
 * Mirrors the app update offer into the sidebar header and forwards the user's request to install it. The update
 * machine runs only on desktop; elsewhere nothing is offered. What to offer and when to restart stays with it.
 */
internal class StudioAppUpdate(private val machines: MachineRegistry) {
    private val log = Log.tag("AiStudioModel")

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun observe(pipeline: PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>) =
        with(pipeline) {
            machines.observe(AppUpdateMachineKey)
                .flatMapLatest { ref -> ref?.state ?: flowOf(null) }
                .map { state -> state?.appUpdateOffer()?.toUi() }
                .distinctUntilChanged()
                .collect { offer -> updateState { copy(appUpdate = offer) } }
        }

    suspend fun install() {
        val result = machines.send(AppUpdateMachineKey, AppUpdateIntent.Public.Install)
        log.i { "app update install requested result=$result" }
    }
}

/** Restarting interrupts ordinary turns and every active organism session, including chats outside the panes. */
internal fun AiStudioScreenState.hasRunningAgentsForUpdate(): Boolean = running.isNotEmpty() || organisms.values.any {
    it.status != OrganismStatusUi.Completed && it.status != OrganismStatusUi.Aborted && it.subSessions.any { session ->
        session.state in setOf(
            SubSessionStateUi.Germinating,
            SubSessionStateUi.Working,
            SubSessionStateUi.AwaitingUser,
            SubSessionStateUi.Judging,
        )
    }
}

internal fun AppUpdateOffer.toUi(): AppUpdateUi {
    val isManual = failure == AppUpdateFailure.ManualInstallRequired
    val stage = when (phase) {
        AppUpdatePhase.Ready -> if (isManual) AppUpdateStageUi.ManualInstall else AppUpdateStageUi.Ready
        is AppUpdatePhase.Downloading -> AppUpdateStageUi.Downloading
        AppUpdatePhase.Preparing -> AppUpdateStageUi.Preparing
        AppUpdatePhase.Restarting -> AppUpdateStageUi.Restarting
    }
    return AppUpdateUi(
        version = version,
        stage = stage,
        percent = (phase as? AppUpdatePhase.Downloading)?.percent,
        failure = failure?.toUi(),
        pageUrl = pageUrl,
    )
}

private fun AppUpdateFailure.toUi(): AppUpdateFailureUi? = when (this) {
    AppUpdateFailure.Network -> AppUpdateFailureUi.Network

    AppUpdateFailure.RateLimited -> AppUpdateFailureUi.RateLimited

    AppUpdateFailure.InvalidRelease -> AppUpdateFailureUi.InvalidRelease

    AppUpdateFailure.Corrupted -> AppUpdateFailureUi.Corrupted

    AppUpdateFailure.NotEnoughSpace -> AppUpdateFailureUi.NotEnoughSpace

    AppUpdateFailure.Storage -> AppUpdateFailureUi.Storage

    AppUpdateFailure.InstallFailed -> AppUpdateFailureUi.InstallFailed

    AppUpdateFailure.PreviousAttemptFailed -> AppUpdateFailureUi.PreviousAttemptFailed

    // The stage already tells the user to install by hand.
    AppUpdateFailure.ManualInstallRequired -> null
}
